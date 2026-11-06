package io.github.jacobosanchez.nyajsw.config;

import java.math.BigInteger;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Typed reads of a {@link LoadedConfig} for the typed views (package-private helper of
 * {@link WrapperConfig}). Each read returns the configured value when it is set and fits its type,
 * else the registry's constant default, else nothing. Values that do not fit were already reported
 * by {@link ConfigValidator}; problems that only show here (an invalid path, an exit code out of
 * range) are reported like the validator does: an error for a supported key, a warning for a key
 * that has no effect yet.
 */
final class ConfigValues {

    private final LoadedConfig config;
    private final KeyRegistry registry;
    private final KeyCollector collector;
    private final Path directory;
    private final SecretMasker masker;
    private final List<ConfigEntry> userEntries;
    private final List<ConfigWarning> warnings = new ArrayList<>();
    private final List<ConfigException> errors = new ArrayList<>();

    ConfigValues(LoadedConfig config, KeyRegistry registry) {
        this.config = Objects.requireNonNull(config, "config");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.collector = new KeyCollector(registry);
        Path parent = config.file().getParent();
        this.directory = parent != null ? parent : config.file().toAbsolutePath().getRoot();
        this.masker = new SecretMasker(registry, config);
        this.userEntries = config.entries().stream()
                .filter(entry -> config.layer(entry.key()).orElseThrow() != ConfigLayer.DEFAULT)
                .toList();
    }

    /** The directory of the main configuration file, against which relative paths resolve (D13). */
    Path directory() {
        return directory;
    }

    Optional<String> string(KeySpec spec) {
        return typed(spec, value -> value);
    }

    /**
     * A text value that means something only when it is not empty (a name, a command, a URL): an
     * empty value gives an empty result, so a derived default computed by the caller applies. A
     * constant default does not: the key was written, and for keys such as {@code
     * wrapper.logfile.format} YAJSW reads a blank value as "off".
     */
    Optional<String> text(KeySpec spec) {
        return string(spec).filter(Predicate.not(String::isEmpty));
    }

    Optional<Integer> integer(KeySpec spec) {
        return typed(spec, ValueParser::parseInteger);
    }

    Optional<Boolean> bool(KeySpec spec) {
        return typed(spec, ValueParser::parseBoolean);
    }

    Optional<Duration> duration(KeySpec spec) {
        return typed(spec, ValueParser::parseDuration);
    }

    Optional<Long> size(KeySpec spec) {
        return typed(spec, ValueParser::parseSize);
    }

    List<String> list(KeySpec spec) {
        return typed(spec, ValueParser::parseList).orElse(List.of());
    }

    <E extends Enum<E>> Optional<E> enumeration(KeySpec spec, Class<E> type) {
        return typed(spec, value -> Enum.valueOf(type, ValueParser.parseEnum(spec, value)));
    }

    /** A path value resolved against {@link #directory()}; an invalid path is reported and ignored. */
    Optional<Path> path(KeySpec spec) {
        return path(spec, directory);
    }

    /** A path value resolved against {@code base}; an invalid path is reported and ignored. */
    Optional<Path> path(KeySpec spec, Path base) {
        Optional<ConfigEntry> entry = entry(spec);
        return typed(spec, ValueParser::normalisePath).flatMap(text -> toPath(spec, entry.orElse(null), text, base));
    }

    /** A value that has a constant default, so it is always present. */
    <T> T required(Optional<T> value, KeySpec spec) {
        return value.orElseThrow(() -> new IllegalStateException(spec.name() + " has no constant default"));
    }

    /** The members of a family in collection order, reporting the collector's warnings. */
    List<KeyCollector.Member> family(KeySpec spec) {
        KeyCollector.Collected collected = collector.collect(spec, userEntries);
        warnings.addAll(collected.warnings());
        return collected.members();
    }

    /**
     * The members of a family in precedence order, for rules where the last spelling wins:
     * configuration order (the {@link LoadedConfig} entry order), with members set by a system
     * property last, since that layer overrides the file (AGENTS.md §4.6). The collector's warnings
     * are reported as by {@link #family}.
     */
    List<KeyCollector.Member> familyInFileOrder(KeySpec spec) {
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < userEntries.size(); i++) {
            positions.put(userEntries.get(i).key(), i);
        }
        List<KeyCollector.Member> members = new ArrayList<>(family(spec));
        members.sort(Comparator
                .comparing((KeyCollector.Member member) -> isSystemProperty(member.key()))
                .thenComparingInt(member -> positions.get(member.key())));
        return members;
    }

    private boolean isSystemProperty(String key) {
        return config.layer(key).orElseThrow() == ConfigLayer.SYSTEM_PROPERTY;
    }

    /**
     * The values of a family in collection order, without empty members: an empty JVM option or
     * classpath entry would break the command line, so {@code wrapper.java.additional.3=} counts as
     * not set (unverified against YAJSW for program parameters, P1.9).
     */
    List<String> familyValues(KeySpec spec) {
        return family(spec).stream().map(KeyCollector.Member::value)
                .filter(value -> !value.isEmpty() && !ValueParser.isUnset(spec, value)).toList();
    }

    /** The paths of a family, resolved against {@code base}; invalid ones are reported and left out. */
    List<Path> familyPaths(KeySpec spec, Path base) {
        List<Path> paths = new ArrayList<>();
        for (KeyCollector.Member member : family(spec)) {
            if (!ValueParser.isUnset(spec, member.value())) {
                toPath(spec, member.entry(), ValueParser.normalisePath(member.value()), base).ifPresent(paths::add);
            }
        }
        return paths;
    }

    /** {@code number} as an {@code int}, or empty with a problem reported when it does not fit. */
    Optional<Integer> intNumber(KeySpec spec, KeyCollector.Member member, BigInteger number) {
        try {
            return Optional.of(number.intValueExact());
        } catch (ArithmeticException e) {
            problem(spec, member.entry().location(), member.key() + ": number out of range; ignored");
            return Optional.empty();
        }
    }

    /**
     * Reports a value problem the validator could not see: an error for a key that takes effect, a
     * warning for one that does not yet ({@link KeyStatus#takesEffect()}).
     */
    void problem(KeySpec spec, ConfigLocation location, String message) {
        if (spec.status().takesEffect()) {
            errors.add(new ConfigException(location, message));
        } else {
            warnings.add(new ConfigWarning(location, message));
        }
    }

    /** Reports something odd that never stops the wrapper, whatever the key's status. */
    void warn(ConfigLocation location, String message) {
        warnings.add(new ConfigWarning(location, message));
    }

    /** {@code value} as it may appear in a message: secrets hidden, control characters escaped. */
    String shown(String key, String value) {
        StringBuilder shown = new StringBuilder();
        for (char c : masker.mask(key, value).toCharArray()) {
            if (Character.isISOControl(c)) {
                shown.append(String.format("\\u%04x", (int) c));
            } else {
                shown.append(c);
            }
        }
        return shown.toString();
    }

    List<ConfigWarning> warnings() {
        return List.copyOf(warnings);
    }

    List<ConfigException> errors() {
        return List.copyOf(errors);
    }

    private Optional<ConfigEntry> entry(KeySpec spec) {
        if (!spec.pattern().isExact()) {
            throw new IllegalArgumentException("not a single key: " + spec.name());
        }
        return config.get(spec.name());
    }

    private <T> Optional<T> typed(KeySpec spec, Function<String, T> parser) {
        Optional<ConfigEntry> entry = entry(spec);
        if (entry.isPresent() && !ValueParser.isUnset(spec, entry.get().value())) {
            try {
                return Optional.of(parser.apply(entry.get().value()));
            } catch (IllegalArgumentException e) {
                // Reported by ConfigValidator; fall back to the default below.
            }
        }
        if (spec.defaultValue() instanceof KeyDefault.Constant constant) {
            return Optional.of(parser.apply(constant.value()));
        }
        return Optional.empty();
    }

    private Optional<Path> toPath(KeySpec spec, ConfigEntry entry, String text, Path base) {
        String key = entry != null ? entry.key() : spec.name();
        ConfigLocation location = entry != null ? entry.location() : ConfigLocation.of("built-in default");
        String reason;
        try {
            Path path = Path.of(text);
            Path resolved = (path.isAbsolute() ? path : base.resolve(path)).normalize();
            if (resolved.isAbsolute()) {
                return Optional.of(resolved);
            }
            // e.g. a drive-relative C:foo while the base is on another drive
            reason = "cannot be made absolute against " + base;
        } catch (InvalidPathException e) {
            reason = e.getReason();
        }
        String shown = registry.isSecret(key) ? "" : " " + shown(key, text);
        problem(spec, location, "invalid path" + shown + " for " + key + ": " + reason + "; ignored");
        return Optional.empty();
    }
}
