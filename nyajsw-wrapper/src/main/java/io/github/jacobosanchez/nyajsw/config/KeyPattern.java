package io.github.jacobosanchez.nyajsw.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The name of a configuration key, either exact ({@code wrapper.java.command}) or a template with
 * placeholders for families of keys ({@code wrapper.java.additional.<n>},
 * {@code wrapper.app.env.<variable>}). The template is also how the key is shown to users.
 *
 * <p>Placeholders are whole dot-separated segments written {@code <label>} with a lower-case label:
 * <ul>
 *   <li>{@code <n>} matches an index: one or more decimal digits, gaps allowed, as in YAJSW's
 *       {@code .N} keys;</li>
 *   <li>{@code <code>} matches an exit code: a decimal integer, optionally negative;</li>
 *   <li>any other label matches one or more non-empty segments (so dots are allowed, empty segments
 *       are not) and only names what goes there.</li>
 * </ul>
 * Key names are compared case-sensitively, as YAJSW does.
 */
public final class KeyPattern {

    /** Label of the placeholder that matches a numeric index. */
    public static final String INDEX = "n";

    /** Label of the placeholder that matches an exit code. */
    public static final String EXIT_CODE = "code";

    /**
     * Orders patterns from the most to the least specific: more literal characters first, then more
     * numeric placeholders ({@code <n>}, {@code <code>}), which accept less than free ones. Patterns
     * equal under this order are equally specific; the registry rejects two such patterns that can
     * match the same key.
     */
    public static final Comparator<KeyPattern> MOST_SPECIFIC_FIRST =
            Comparator.comparingInt(KeyPattern::literalLength)
                    .thenComparingInt(KeyPattern::numericPlaceholders)
                    .reversed();

    private static final Pattern PLACEHOLDER = Pattern.compile("<([a-z]+)>");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_]+");
    /** Example value for any placeholder; it is accepted by every placeholder kind. */
    private static final String NUMERIC_EXAMPLE = "1";

    private final String template;
    /** The template split at dots. */
    private final List<String> segments;
    /** {@code null} for an exact name. */
    private final Pattern regex;
    private final int literalLength;
    private final int numericPlaceholders;
    private final String example;
    /** Placeholder labels in template order, e.g. {@code [n]}. */
    private final List<String> labels;

    private KeyPattern(String template, List<String> segments, Pattern regex, int literalLength,
            int numericPlaceholders, String example, List<String> labels) {
        this.template = template;
        this.segments = segments;
        this.regex = regex;
        this.literalLength = literalLength;
        this.numericPlaceholders = numericPlaceholders;
        this.example = example;
        this.labels = labels;
    }

    /**
     * Parses a template.
     *
     * @throws IllegalArgumentException if the template is empty, has an empty segment, a segment
     *                                  with characters other than letters, digits and {@code _}, a
     *                                  placeholder that is not a whole segment, or starts with a
     *                                  placeholder
     */
    public static KeyPattern of(String template) {
        Objects.requireNonNull(template, "template");
        List<String> segments = List.of(template.split("\\.", -1));
        StringBuilder regex = new StringBuilder();
        StringBuilder example = new StringBuilder();
        int literalLength = segments.size() - 1; // the dots
        int numericPlaceholders = 0;
        List<String> labels = new ArrayList<>();
        boolean exact = true;
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            if (i > 0) {
                regex.append("\\.");
                example.append('.');
            }
            Matcher placeholder = PLACEHOLDER.matcher(segment);
            if (placeholder.matches()) {
                if (i == 0) {
                    throw invalid(template, "the first segment must not be a placeholder");
                }
                String label = placeholder.group(1);
                switch (label) {
                    case INDEX -> regex.append("([0-9]+)");
                    case EXIT_CODE -> regex.append("(-?[0-9]+)");
                    // No repeated group: that recurses once per segment in java.util.regex and can
                    // overflow the stack on absurd keys. Empty segments are excluded up front instead.
                    default -> regex.append("([^.](?:.*[^.])?)");
                }
                labels.add(label);
                boolean numeric = isNumericLabel(label);
                numericPlaceholders += numeric ? 1 : 0;
                example.append(numeric ? NUMERIC_EXAMPLE : label);
                exact = false;
            } else if (SEGMENT.matcher(segment).matches()) {
                regex.append(Pattern.quote(segment));
                example.append(segment);
                literalLength += segment.length();
            } else {
                throw invalid(template, "segment '" + segment + "' must be a placeholder such as <n>, or"
                        + " non-empty and use only letters, digits and '_'");
            }
        }
        return exact
                ? new KeyPattern(template, segments, null, template.length(), 0, template, List.of())
                : new KeyPattern(template, segments, Pattern.compile(regex.toString()), literalLength,
                        numericPlaceholders, example.toString(), List.copyOf(labels));
    }

    private static boolean isNumericLabel(String label) {
        return label.equals(INDEX) || label.equals(EXIT_CODE);
    }

    private static IllegalArgumentException invalid(String template, String reason) {
        return new IllegalArgumentException("invalid key template '" + template + "': " + reason);
    }

    /** The template as written, e.g. {@code wrapper.java.additional.<n>}. */
    public String template() {
        return template;
    }

    /** Whether the template has no placeholders and therefore names exactly one key. */
    public boolean isExact() {
        return regex == null;
    }

    /** Whether {@code key} is this key or a member of this family of keys. */
    public boolean matches(String key) {
        Objects.requireNonNull(key, "key");
        return regex == null ? template.equals(key) : hasNoEmptySegment(key) && regex.matcher(key).matches();
    }

    /** Templates have no empty segments and placeholders match non-empty ones, so such keys never match. */
    private static boolean hasNoEmptySegment(String key) {
        return !key.contains("..") && !key.startsWith(".") && !key.endsWith(".");
    }

    /**
     * Number of characters outside placeholders. When several templates match a key, the one with
     * more literal characters is the more specific one (see {@link #MOST_SPECIFIC_FIRST}).
     */
    public int literalLength() {
        return literalLength;
    }

    /** Number of {@code <n>} and {@code <code>} placeholders. */
    public int numericPlaceholders() {
        return numericPlaceholders;
    }

    /**
     * What the placeholders stand for in a concrete key, in template order: {@code ["3"]} for
     * {@code wrapper.java.additional.3} against {@code wrapper.java.additional.<n>},
     * {@code ["PATH"]} for {@code wrapper.app.env.PATH}. An exact name gives an empty list.
     *
     * @return the placeholder values, or empty when {@code key} does not match
     */
    public Optional<List<String>> captures(String key) {
        Objects.requireNonNull(key, "key");
        if (regex == null) {
            return template.equals(key) ? Optional.of(List.of()) : Optional.empty();
        }
        Matcher matcher = regex.matcher(key);
        if (!hasNoEmptySegment(key) || !matcher.matches()) {
            return Optional.empty();
        }
        List<String> values = new ArrayList<>(matcher.groupCount());
        for (int group = 1; group <= matcher.groupCount(); group++) {
            values.add(matcher.group(group));
        }
        return Optional.of(List.copyOf(values));
    }

    /** The placeholder labels in template order, e.g. {@code [n]}; empty for an exact name. */
    public List<String> labels() {
        return labels;
    }

    /** Whether the placeholder at {@code position} (see {@link #labels()}) only matches integers. */
    public boolean isNumeric(int position) {
        return isNumericLabel(labels.get(position));
    }

    /**
     * A concrete key this template matches: numeric placeholders replaced by {@code 1}, other
     * placeholders by their label (e.g. {@code wrapper.app.env.variable}).
     */
    public String example() {
        return example;
    }

    /**
     * A key that both this pattern and {@code other} match, if one exists with the same number of
     * segments: each segment takes the literal of whichever pattern has one there, and {@code 1}
     * where both have placeholders. This finds every overlap between templates whose placeholders
     * cover one segment each; free placeholders that span several segments are only covered when
     * the overlap also exists with one segment per placeholder.
     */
    public Optional<String> commonExample(KeyPattern other) {
        Objects.requireNonNull(other, "other");
        if (segments.size() != other.segments.size()) {
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            String mine = segments.get(i);
            String theirs = other.segments.get(i);
            boolean minePlaceholder = PLACEHOLDER.matcher(mine).matches();
            boolean theirsPlaceholder = PLACEHOLDER.matcher(theirs).matches();
            if (!minePlaceholder) {
                parts.add(mine);
            } else if (!theirsPlaceholder) {
                parts.add(theirs);
            } else {
                parts.add(NUMERIC_EXAMPLE);
            }
        }
        String candidate = String.join(".", parts);
        return matches(candidate) && other.matches(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KeyPattern pattern && pattern.template.equals(template);
    }

    @Override
    public int hashCode() {
        return template.hashCode();
    }

    @Override
    public String toString() {
        return template;
    }
}
