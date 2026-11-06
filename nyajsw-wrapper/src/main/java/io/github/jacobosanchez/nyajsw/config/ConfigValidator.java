package io.github.jacobosanchez.nyajsw.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Judges what the operator configured, against the key registry (plan §3.1, AGENTS.md §4.6):
 *
 * <ul>
 *   <li>An unknown key in the {@code wrapper.*} namespace is a warning; other unknown keys are plain
 *       variables (YAJSW lets any key be used as <code>${name}</code>) and pass silently.</li>
 *   <li>Planned, ignored and unsupported keys are a warning naming the key and its status, once per
 *       declaration (a family such as {@code wrapper.java.monitor.<setting>} warns once, with the
 *       number of keys and the first location).</li>
 *   <li>{@link KeyStatus#UNSUPPORTED_FATAL} keys with a value are errors; set to nothing they change
 *       nothing and only warn.</li>
 *   <li>A value of a supported key must fit its type ({@link ValueParser}), or it is an error. A
 *       planned key has no effect yet, so a value that does not fit only warns; values of ignored and
 *       unsupported keys are not checked. A value that leaves the key unset
 *       ({@link ValueParser#isUnset}) is never checked.</li>
 *   <li>A secret key set by a system property is a warning, known key or not: the wrapper command line
 *       (for a service, its registered command) is readable by other local users.</li>
 * </ul>
 *
 * <p>Entries of the {@link ConfigLayer#DEFAULT} layer were written by nobody and are skipped. Errors
 * come in configuration order. Messages show a rejected value only for keys that are not secret, and
 * with secrets hidden ({@link SecretMasker}).
 */
public final class ConfigValidator {

    private final KeyRegistry registry;

    public ConfigValidator(KeyRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Judges {@code config}; the warnings of loading it ({@link LoadedConfig#warnings()}) are not repeated. */
    public Result validate(LoadedConfig config) {
        Objects.requireNonNull(config, "config");
        SecretMasker masker = new SecretMasker(registry, config);
        List<ConfigWarning> warnings = new ArrayList<>();
        Map<Integer, ConfigException> errors = new TreeMap<>(); // by entry position
        Map<KeySpec, List<Integer>> byDeclaration = new LinkedHashMap<>();
        List<ConfigEntry> entries = config.entries();
        for (int position = 0; position < entries.size(); position++) {
            ConfigEntry entry = entries.get(position);
            ConfigLayer layer = config.layer(entry.key()).orElseThrow();
            if (layer == ConfigLayer.DEFAULT) {
                continue;
            }
            if (layer == ConfigLayer.SYSTEM_PROPERTY && registry.isSecret(entry.key())) {
                warnings.add(new ConfigWarning(entry.location(), entry.key() + " is set by a system property on"
                        + " the wrapper command line, which other local users can read; set it in the"
                        + " configuration file instead"));
            }
            KeySpec spec = registry.lookup(entry.key()).orElse(null);
            if (spec == null) {
                if (SystemPropertyConfigSource.isConfigurationKey(entry.key())) {
                    warnings.add(new ConfigWarning(entry.location(), "unknown key " + entry.key()
                            + "; it has no effect"));
                }
                continue;
            }
            byDeclaration.computeIfAbsent(spec, ignored -> new ArrayList<>()).add(position);
            if ((spec.status() == KeyStatus.SUPPORTED || spec.status() == KeyStatus.PLANNED)
                    && !ValueParser.isUnset(spec, entry.value())) {
                Optional<String> problem = valueProblem(spec, entry, masker);
                if (problem.isPresent()) {
                    if (spec.status() == KeyStatus.SUPPORTED) {
                        errors.put(position, new ConfigException(entry.location(), problem.get()));
                    } else {
                        warnings.add(new ConfigWarning(entry.location(), problem.get()));
                    }
                }
            }
        }
        byDeclaration.forEach((spec, positions) -> judgeStatus(spec, positions, entries, warnings, errors));
        return new Result(warnings, List.copyOf(errors.values()));
    }

    private Optional<String> valueProblem(KeySpec spec, ConfigEntry entry, SecretMasker masker) {
        try {
            ValueParser.parse(spec, entry.value());
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            String shown = registry.isSecret(entry.key()) ? "" : " '" + masker.mask(entry.key(), entry.value()) + "'";
            return Optional.of("invalid value" + shown + " for " + entry.key() + ": " + e.getMessage());
        }
    }

    private static void judgeStatus(KeySpec spec, List<Integer> positions, List<ConfigEntry> entries,
            List<ConfigWarning> warnings, Map<Integer, ConfigException> errors) {
        int firstPosition = positions.get(0);
        ConfigLocation first = entries.get(firstPosition).location();
        String subject = spec.name() + (spec.pattern().isExact() ? ""
                : " (" + positions.size() + (positions.size() == 1 ? " key" : " keys") + ")");
        String notes = spec.notes().isEmpty() ? "" : ". " + spec.notes();
        switch (spec.status()) {
            case SUPPORTED -> {
            }
            case PLANNED -> warnings.add(new ConfigWarning(first, subject
                    + " is not implemented yet in this version of nyajsw; it has no effect" + notes));
            case IGNORED -> warnings.add(new ConfigWarning(first, subject + " is ignored by nyajsw" + notes));
            case UNSUPPORTED -> warnings.add(new ConfigWarning(first, subject
                    + " is not supported by nyajsw; it has no effect" + notes));
            case UNSUPPORTED_FATAL -> {
                boolean allEmpty = positions.stream().allMatch(position -> entries.get(position).value().isEmpty());
                if (allEmpty) {
                    warnings.add(new ConfigWarning(first, subject + " is not supported by nyajsw; it is empty and"
                            + " has no effect"));
                } else {
                    errors.put(firstPosition, new ConfigException(first, subject
                            + " is not supported by nyajsw and cannot be ignored" + notes));
                }
            }
        }
    }

    /**
     * The outcome of {@link #validate}.
     *
     * @param warnings problems that do not stop the wrapper, unmodifiable
     * @param errors   problems that do, unmodifiable
     */
    public record Result(List<ConfigWarning> warnings, List<ConfigException> errors) {

        public Result {
            warnings = List.copyOf(warnings);
            errors = List.copyOf(errors);
        }

        /** @throws ConfigException listing every error, if there is any */
        public void throwIfErrors() {
            if (!errors.isEmpty()) {
                throw ConfigException.aggregate(errors);
            }
        }
    }
}
