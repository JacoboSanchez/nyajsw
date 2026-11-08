package io.github.jacobosanchez.nyajsw.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts raw configuration values to the type their {@link KeySpec} declares (plan §4.5, typed
 * views). Every method throws {@link IllegalArgumentException} with a reason that never contains the
 * value, so callers decide whether the value may be shown (it may not for secret keys).
 *
 * <p>Rules, chosen to accept what YAJSW files contain:
 * <ul>
 *   <li>{@link KeyType#INTEGER}: a decimal {@code int}, optionally signed.</li>
 *   <li>{@link KeyType#BOOLEAN}: {@code true}/{@code false}, also {@code yes}/{@code no} and
 *       {@code on}/{@code off}, in any case, as Commons Configuration (which YAJSW uses) accepts.</li>
 *   <li>{@link KeyType#DURATION}: a non-negative whole number of seconds.</li>
 *   <li>{@link KeyType#SIZE}: a non-negative number of bytes with an optional {@code K}, {@code M} or
 *       {@code G} suffix (binary multiples), in any case.</li>
 *   <li>{@link KeyType#ENUM}: a declared value or alias, in any case, returned in canonical form.</li>
 *   <li>{@link KeyType#PATH}: see {@link #normalisePath(String)}.</li>
 *   <li>{@link KeyType#LIST}: comma-separated, items trimmed, empty items dropped.</li>
 *   <li>{@link KeyType#STRING}: as it is.</li>
 * </ul>
 */
public final class ValueParser {

    private static final Pattern INTEGER = Pattern.compile("[+-]?[0-9]+");
    private static final Pattern NON_NEGATIVE = Pattern.compile("\\+?[0-9]+");
    private static final Pattern SIZE = Pattern.compile("([0-9]+)([KkMmGg]?)");
    private static final List<String> TRUE = List.of("true", "yes", "on");
    private static final List<String> FALSE = List.of("false", "no", "off");

    private ValueParser() {
    }

    /**
     * Checks {@code value} against the type of {@code spec} and returns it converted: an
     * {@link Integer}, {@link Boolean}, {@link Duration}, {@link Long} (bytes), canonical enum
     * {@link String}, normalised path {@link String}, {@link List} of strings, or the string itself.
     */
    public static Object parse(KeySpec spec, String value) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(value, "value");
        return spec.type() == KeyType.ENUM ? parseEnum(spec, value) : parseScalar(spec.type(), value);
    }

    /**
     * Like {@link #parse(KeySpec, String)} for every type but {@link KeyType#ENUM}, whose values
     * belong to a declaration. {@link KeySpec} uses it to check constant defaults, so defaults and
     * configured values follow the same rules.
     *
     * @throws IllegalArgumentException also for {@link KeyType#ENUM}
     */
    public static Object parseScalar(KeyType type, String value) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        return switch (type) {
            case STRING -> value;
            case INTEGER -> parseInteger(value);
            case BOOLEAN -> parseBoolean(value);
            case DURATION -> parseDuration(value);
            case SIZE -> parseSize(value);
            case PATH -> normalisePath(value);
            case LIST -> parseList(value);
            case ENUM -> throw new IllegalArgumentException("enumeration values belong to a declaration");
        };
    }

    /**
     * Whether {@code value} leaves {@code spec} unset, so that its default applies: an empty value of
     * any type but {@link KeyType#STRING} (where empty is a value), or a list without items.
     */
    public static boolean isUnset(KeySpec spec, String value) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(value, "value");
        return switch (spec.type()) {
            case STRING -> false;
            case LIST -> parseList(value).isEmpty();
            default -> value.isEmpty();
        };
    }

    public static int parseInteger(String value) {
        if (!INTEGER.matcher(value).matches()) {
            throw new IllegalArgumentException("expected a whole number");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // No cause: its message would contain the value.
            throw new IllegalArgumentException("number out of range");
        }
    }

    public static boolean parseBoolean(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (TRUE.contains(lower)) {
            return true;
        }
        if (FALSE.contains(lower)) {
            return false;
        }
        throw new IllegalArgumentException("expected true or false");
    }

    public static Duration parseDuration(String value) {
        if (!NON_NEGATIVE.matcher(value).matches()) {
            throw new IllegalArgumentException("expected a whole number of seconds, 0 or more");
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("number of seconds out of range");
        }
    }

    /** @return the size in bytes */
    public static long parseSize(String value) {
        Matcher matcher = SIZE.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("expected a size such as 512, 10K, 10M or 1G");
        }
        int shift = switch (matcher.group(2).toUpperCase(Locale.ROOT)) {
            case "K" -> 10;
            case "M" -> 20;
            case "G" -> 30;
            default -> 0;
        };
        try {
            long number = Long.parseLong(matcher.group(1));
            if (number > (Long.MAX_VALUE >> shift)) {
                throw new IllegalArgumentException("size out of range");
            }
            return number << shift;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("size out of range");
        }
    }

    /** @return the canonical value */
    public static String parseEnum(KeySpec spec, String value) {
        if (spec.type() != KeyType.ENUM) {
            throw new IllegalArgumentException(spec.name() + " is not an enumeration");
        }
        return spec.canonicalValue(value).orElseThrow(() -> new IllegalArgumentException("expected one of "
                + String.join(", ", spec.values())
                + (spec.aliases().isEmpty() ? "" : " (or " + String.join(", ", spec.aliases().keySet()) + ")")));
    }

    /**
     * Tolerates doubled backslashes in a path. Values read from a file have their escapes undone
     * already ({@code FileConfigSource}, plan §4.5), so this matters for values from system properties
     * and the environment, written as in a file ({@code -Dwrapper.working.dir=C:\\app}), and for
     * doubled separators that remain. Every pair becomes one backslash, except a leading pair followed
     * by another character: that is a UNC or {@code \\?\} prefix ({@code \\server\share\\} gives
     * {@code \\server\share\}). Relative paths are resolved later, against the configuration file's
     * directory (D13).
     */
    public static String normalisePath(String value) {
        boolean literalPrefix = value.length() > 2 && value.startsWith("\\\\") && value.charAt(2) != '\\';
        if (literalPrefix) {
            return "\\\\" + value.substring(2).replace("\\\\", "\\");
        }
        return value.replace("\\\\", "\\");
    }

    /** {@code text} without one pair of double quotes around all of it, if it has them. */
    public static String unquote(String text) {
        return text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")
                ? text.substring(1, text.length() - 1)
                : text;
    }

    /**
     * Splits a command line into the program and its arguments, for running it without a shell
     * (AGENTS.md §4.7): blanks separate the arguments, double quotes group blanks into one argument
     * and are removed ({@code "C:/Program Files/x.exe" -v} is two arguments, {@code ""} an empty
     * one). There is no escape character, so backslashes stay as they are and a double quote cannot
     * be part of an argument.
     *
     * @return the arguments, empty for a blank value; unmodifiable
     * @throws IllegalArgumentException when a double quote is not closed; the reason does not quote the value
     */
    public static List<String> parseCommand(String value) {
        List<String> arguments = new ArrayList<>();
        StringBuilder argument = new StringBuilder();
        boolean inArgument = false;
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                inArgument = true;
            } else if (!quoted && Character.isWhitespace(c)) {
                if (inArgument) {
                    arguments.add(argument.toString());
                    argument.setLength(0);
                    inArgument = false;
                }
            } else {
                argument.append(c);
                inArgument = true;
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("a double quote is not closed");
        }
        if (inArgument) {
            arguments.add(argument.toString());
        }
        return List.copyOf(arguments);
    }

    /** @return the items, trimmed, without empty ones; unmodifiable */
    public static List<String> parseList(String value) {
        return Arrays.stream(value.split(",", -1)).map(FileConfigSource::trim).filter(item -> !item.isEmpty()).toList();
    }
}
