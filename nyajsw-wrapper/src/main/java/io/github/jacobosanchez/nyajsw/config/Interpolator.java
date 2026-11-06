package io.github.jacobosanchez.nyajsw.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves <code>${...}</code> variable references in configuration values (plan §4.5).
 *
 * <ul>
 *   <li><code>${name}</code> is looked up, in order, among the configuration's own keys (YAJSW lets
 *       any key act as a variable, e.g. {@code APP_HOME=C:/app} then <code>${APP_HOME}/lib</code>),
 *       the wrapper JVM's system properties and the environment variables.</li>
 *   <li><code>${sys:name}</code> reads only a system property and <code>${env:NAME}</code> only an
 *       environment variable; their names are taken as they are, so
 *       <code>${env:ProgramFiles(x86)}</code> works.</li>
 *   <li>Values taken from configuration keys are resolved themselves, recursively; a reference cycle
 *       is an error. That includes keys set by system properties, which {@link ConfigLoader} merges
 *       as entries. Values read through the system-property and environment lookups
 *       (<code>${sys:name}</code>, <code>${env:NAME}</code>, or a name no key has) are used as they
 *       are.</li>
 *   <li>A reference that cannot be resolved is kept as written, with a warning. A <code>${</code>
 *       without a closing brace is kept as written, with a warning. Nested references are not
 *       supported: in <code>${a${b}}</code> only the inner reference is resolved and the outer
 *       <code>${</code> is kept as written, with a warning.</li>
 *   <li>A reference that resolves to nothing and looks like code (parentheses, quotes, {@code ;},
 *       braces or brackets, or a leading {@code if}, {@code new} or {@code def} followed by a space)
 *       is a Groovy expression, which YAJSW evaluates and nyajsw does not support. That is an error,
 *       because using the text as it is would silently change behaviour. The same holds for code
 *       around a nested reference, such as <code>${util.f("${x}")}</code>.</li>
 *   <li>A reference to YAJSW's keystore, <code>${keystore name}</code> (any case, also around a
 *       nested reference), that resolves to nothing is an error for the same reason: the text would
 *       otherwise be used as a password.</li>
 *   <li>Environment variable names are compared case-insensitively when the environment is (as on
 *       Windows); an exact match wins.</li>
 *   <li>There is no escape syntax: a literal <code>${x}</code> is only kept when {@code x} resolves
 *       to nothing (with a warning).</li>
 * </ul>
 *
 * <p>Warnings never contain values, and error messages only show the offending expression of
 * non-secret keys. For keys the registry marks as secret, messages name the key but neither the
 * expression nor the names it refers to, which are part of the value.
 */
public final class Interpolator {

    private static final String START = "${";
    private static final char END = '}';
    private static final String SYS_PREFIX = "sys:";
    private static final String ENV_PREFIX = "env:";
    private static final Pattern CODE_LIKE = Pattern.compile("[()\"';{}\\[\\]]|^\\s*(if|new|def)\\s");
    private static final Pattern KEYSTORE = Pattern.compile("(?i)^\\s*keystore\\s");

    private final Map<String, String> systemProperties;
    private final Map<String, String> environment;
    /** The environment keyed case-insensitively, or {@code null} when names are case-sensitive. */
    private final Map<String, String> environmentIgnoringCase;
    private final KeyRegistry registry;

    /**
     * @param systemProperties             the wrapper JVM's system properties
     * @param environment                  the wrapper's environment variables
     * @param caseInsensitiveEnvironment   whether environment variable names are case-insensitive
     * @param registry                     used to recognise secret keys
     */
    public Interpolator(Map<String, String> systemProperties, Map<String, String> environment,
            boolean caseInsensitiveEnvironment, KeyRegistry registry) {
        this.systemProperties = Map.copyOf(systemProperties);
        this.environment = Map.copyOf(environment);
        this.environmentIgnoringCase = caseInsensitiveEnvironment ? ignoringCase(environment) : null;
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * An interpolator over this JVM's system properties and environment, case-insensitive for
     * environment names on Windows, with the nyajsw key registry. The operating-system check moves to
     * the {@code platform} abstraction once it exists (P5.1).
     */
    public static Interpolator forCurrentProcess() {
        return new Interpolator(currentSystemProperties(), System.getenv(), currentEnvironmentIgnoresCase(),
                ConfigKeys.registry());
    }

    /** A snapshot of this JVM's string-valued system properties. */
    static Map<String, String> currentSystemProperties() {
        Map<String, String> properties = new HashMap<>();
        System.getProperties().forEach((name, value) -> {
            if (name instanceof String key && value instanceof String text) {
                properties.put(key, text);
            }
        });
        return properties;
    }

    /** Whether this process's environment variable names are case-insensitive (Windows). */
    static boolean currentEnvironmentIgnoresCase() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * A case-insensitive view of the environment. Names that differ only in case cannot occur in a
     * Windows environment, the only case-insensitive one; if a caller passes them anyway, the exact
     * lookup in {@link #environmentVariable} still finds each of them.
     */
    private static Map<String, String> ignoringCase(Map<String, String> environment) {
        Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        map.putAll(environment);
        return Collections.unmodifiableMap(map);
    }

    private Optional<String> environmentVariable(String name) {
        String value = environment.get(name);
        if (value == null && environmentIgnoringCase != null) {
            value = environmentIgnoringCase.get(name);
        }
        return Optional.ofNullable(value);
    }

    /**
     * Resolves every value of {@code entries}, which may refer to each other.
     *
     * @param entries the effective entries, at most one per key (layering has already chosen)
     * @return the entries in the same order with resolved values, and the warnings raised; each
     *         problem is reported once, at the entry whose value contains it
     * @throws IllegalArgumentException if a key occurs twice
     * @throws ConfigException          on a reference cycle, a Groovy expression or a keystore reference
     */
    public Result interpolate(List<ConfigEntry> entries) {
        Map<String, ConfigEntry> byKey = new LinkedHashMap<>();
        for (ConfigEntry entry : entries) {
            if (byKey.putIfAbsent(entry.key(), entry) != null) {
                throw new IllegalArgumentException("key occurs twice, merge layers first: " + entry.key());
            }
        }
        Expansion expansion = new Expansion(key -> Optional.ofNullable(byKey.get(key)), true);
        List<ConfigEntry> resolved = new ArrayList<>(entries.size());
        for (ConfigEntry entry : entries) {
            String value = expansion.entryValue(entry).value();
            resolved.add(value.equals(entry.value()) ? entry : new ConfigEntry(entry.key(), value, entry.location()));
        }
        return new Result(resolved, expansion.warnings,
                expansion.externalSecrets.stream().map(Secret::new).collect(Collectors.toSet()));
    }

    /**
     * Resolves one text that is not the value of a key, such as an include path. Problems inside the
     * values of referenced keys are not warned about here, because interpolating the configuration
     * reports them at their own entries; references they leave unresolved do count as unresolved.
     *
     * @param text     the text to resolve
     * @param location where the text was written, for messages
     * @param config   the configuration entries visible at that point, by key
     * @return the resolved text, the references left unresolved, and warnings about the text itself
     * @throws ConfigException on a reference cycle, a Groovy expression or a keystore reference
     */
    public Resolution resolve(String text, ConfigLocation location, Function<String, Optional<ConfigEntry>> config) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(location, "location");
        Expansion expansion = new Expansion(Objects.requireNonNull(config, "config"), false);
        Expanded expanded = expansion.expand(text, location, null, expansion.warnings);
        return new Resolution(expanded.value(), List.copyOf(expanded.unresolved()), expansion.warnings,
                expansion.usedSecret);
    }

    /**
     * The outcome of {@link #interpolate(List)}.
     *
     * @param entries         the entries with resolved values, in input order, unmodifiable
     * @param warnings        warnings in the order they were raised, unmodifiable
     * @param externalSecrets values read from system properties or environment variables whose names
     *                        the registry marks as secret (<code>${env:wrapper.app.password}</code>,
     *                        <code>${sys:db.password}</code>): they are no configuration entries, yet
     *                        copies of them in other values must be hidden too ({@link SecretMasker})
     */
    public record Result(List<ConfigEntry> entries, List<ConfigWarning> warnings, Set<Secret> externalSecrets) {

        public Result {
            entries = List.copyOf(entries);
            warnings = List.copyOf(warnings);
            externalSecrets = Set.copyOf(externalSecrets);
        }
    }

    /**
     * The outcome of {@link #resolve}.
     *
     * @param value      the text with every resolvable reference replaced
     * @param unresolved the names of references kept as written, in the text or in the values of keys
     *                   it refers to, in order of discovery and without repetition; the caller decides
     *                   whether that is a warning or an error
     * @param warnings   warnings about the text itself, such as a <code>${</code> without a closing brace
     * @param usesSecret whether resolving the text read a secret: the value of a key the registry
     *                   marks as secret, or a system property or environment variable of such a name
     *                   (e.g. <code>${sys:wrapper.ntservice.password}</code>), directly or through
     *                   the values of other keys. The value then contains (part of) a secret and must
     *                   not be shown. A secret under a name the registry cannot recognise
     *                   (<code>${env:DB_PASS}</code>) is not detected.
     */
    public record Resolution(String value, List<String> unresolved, List<ConfigWarning> warnings, boolean usesSecret) {

        public Resolution {
            Objects.requireNonNull(value, "value");
            unresolved = List.copyOf(unresolved);
            warnings = List.copyOf(warnings);
        }

        /** Whether every reference was resolved, including those inside referenced keys. */
        public boolean complete() {
            return unresolved.isEmpty();
        }
    }

    /**
     * A resolved text and the references it still contains: those written in it and, through the keys
     * it refers to, those written in their values.
     */
    private record Expanded(String value, Set<String> unresolved) {
    }

    /** One resolution run: the configuration lookup, resolved keys so far and the reference chain. */
    private final class Expansion {

        private final Function<String, Optional<ConfigEntry>> config;
        /** Whether problems in the values of keys are reported (interpolation) or left to it (resolve). */
        private final boolean reportEntryProblems;
        private final Map<String, Expanded> resolvedKeys = new HashMap<>();
        private final Deque<String> chain = new ArrayDeque<>();
        private final List<ConfigWarning> warnings = new ArrayList<>();
        /** Whether a secret key's value was read during this run. */
        private boolean usedSecret;
        /** Non-empty values read from system properties or the environment under secret names. */
        private final Set<String> externalSecrets = new LinkedHashSet<>();

        Expansion(Function<String, Optional<ConfigEntry>> config, boolean reportEntryProblems) {
            this.config = config;
            this.reportEntryProblems = reportEntryProblems;
        }

        /** The resolved value of an entry; its problems are reported once, at the entry. */
        Expanded entryValue(ConfigEntry entry) {
            if (secret(entry.key())) {
                usedSecret = true;
            }
            Expanded known = resolvedKeys.get(entry.key());
            if (known != null) {
                return known;
            }
            if (chain.contains(entry.key())) {
                throw cycle(entry);
            }
            chain.addLast(entry.key());
            List<ConfigWarning> entryWarnings = reportEntryProblems ? warnings : new ArrayList<>();
            Set<String> direct = new LinkedHashSet<>();
            Expanded expanded;
            try {
                expanded = expand(entry.value(), entry.location(), entry.key(), entryWarnings, direct);
            } finally {
                chain.removeLast();
            }
            for (String name : direct) {
                entryWarnings.add(new ConfigWarning(entry.location(), secret(entry.key())
                        ? "unresolved variable in the value of " + entry.key() + "; kept as written"
                        : "unresolved variable ${" + name + "} in the value of " + entry.key() + "; kept as written"));
            }
            resolvedKeys.put(entry.key(), expanded);
            return expanded;
        }

        private ConfigException cycle(ConfigEntry entry) {
            boolean anySecret = secret(entry.key()) || chain.stream().anyMatch(this::secret);
            if (anySecret) {
                return new ConfigException(entry.location(), "variable reference cycle through the value of "
                        + entry.key() + ", which involves a secret key");
            }
            String path = String.join(" -> ", chain) + " -> " + entry.key();
            return new ConfigException(entry.location(), "variable reference cycle: " + path
                    + (chain.size() == 1 ? "; use ${env:NAME} or ${sys:name} to read the environment"
                            + " variable or system property of the same name" : ""));
        }

        Expanded expand(String text, ConfigLocation location, String ownerKey, List<ConfigWarning> sink) {
            return expand(text, location, ownerKey, sink, new LinkedHashSet<>());
        }

        /**
         * @param ownerKey the key whose value this is, or {@code null} for other text
         * @param sink     receives warnings about this text
         * @param direct   receives the names of references written in this text and kept as written
         */
        private Expanded expand(String text, ConfigLocation location, String ownerKey, List<ConfigWarning> sink,
                Set<String> direct) {
            StringBuilder out = new StringBuilder(text.length());
            Set<String> unresolved = new LinkedHashSet<>(); // direct and chained, in order of discovery
            int position = 0;
            while (position < text.length()) {
                int start = text.indexOf(START, position);
                if (start < 0) {
                    out.append(text, position, text.length());
                    break;
                }
                out.append(text, position, start);
                int end = text.indexOf(END, start + START.length());
                if (end < 0) {
                    sink.add(new ConfigWarning(location, "'${' without a closing '}'" + describe(ownerKey)
                            + "; kept as written"));
                    out.append(text, start, text.length());
                    break;
                }
                String name = text.substring(start + START.length(), end);
                int nested = name.indexOf(START);
                if (nested >= 0) {
                    rejectUnsupported(name.substring(0, nested), text.substring(start, end + 1), location, ownerKey);
                    sink.add(new ConfigWarning(location, "nested variable references are not supported"
                            + describe(ownerKey) + "; the outer '${' is kept as written"));
                    int literalEnd = start + START.length() + nested;
                    out.append(text, start, literalEnd);
                    position = literalEnd;
                    continue;
                }
                Optional<Expanded> value = lookup(name);
                if (value.isPresent()) {
                    out.append(value.get().value());
                    unresolved.addAll(value.get().unresolved());
                } else {
                    if (!isPrefixed(name)) {
                        rejectUnsupported(name, text.substring(start, end + 1), location, ownerKey);
                    }
                    direct.add(name);
                    unresolved.add(name);
                    out.append(text, start, end + 1);
                }
                position = end + 1;
            }
            return new Expanded(out.toString(), unresolved);
        }

        /**
         * Rejects text that YAJSW would evaluate and nyajsw cannot: a keystore reference, checked first
         * so that a quoted name still gets the keystore message, or Groovy code.
         *
         * @param name       what stands between <code>${</code> and <code>}</code>, or before a nested
         *                   reference
         * @param expression the whole reference, for the message
         */
        private void rejectUnsupported(String name, String expression, ConfigLocation location, String ownerKey) {
            if (KEYSTORE.matcher(name).find()) {
                throw unsupported(location, ownerKey, expression, "YAJSW keystore references (${keystore ...}) are",
                        "set the value in the configuration, or read it from the wrapper's environment or"
                                + " system properties (${env:NAME}, ${sys:name})");
            }
            if (CODE_LIKE.matcher(name).find()) {
                throw unsupported(location, ownerKey, expression, "Groovy expressions in ${...} are",
                        "replace the expression with a literal value");
            }
        }

        /** The expression is left out for secret keys: it names where the secret comes from. */
        private ConfigException unsupported(ConfigLocation location, String ownerKey, String expression,
                String what, String remedy) {
            return new ConfigException(location, what + " not supported"
                    + (ownerKey != null && secret(ownerKey) ? "" : ": " + expression)
                    + describe(ownerKey) + "; " + remedy);
        }

        private Optional<Expanded> lookup(String name) {
            String bare = isPrefixed(name) ? name.substring(SYS_PREFIX.length()) : name;
            boolean secretName = !bare.isEmpty() && secret(bare);
            if (secretName) {
                // Whatever source answers (a key, ${sys:...}, ${env:...}), a secret name yields a secret.
                usedSecret = true;
            }
            if (name.startsWith(SYS_PREFIX)) {
                return external(systemProperties.get(bare), secretName);
            }
            if (name.startsWith(ENV_PREFIX)) {
                return external(environmentVariable(bare).orElse(null), secretName);
            }
            if (name.isEmpty()) {
                return Optional.empty();
            }
            Optional<ConfigEntry> entry = config.apply(name);
            if (entry.isPresent()) {
                return Optional.of(entryValue(entry.get()));
            }
            String property = systemProperties.get(name);
            return external(property != null ? property : environmentVariable(name).orElse(null), secretName);
        }

        /** A value from outside the configuration; remembered for masking when its name is secret. */
        private Optional<Expanded> external(String value, boolean secretName) {
            if (secretName && value != null && !value.isEmpty()) {
                externalSecrets.add(value);
            }
            return literal(value);
        }

        private Optional<Expanded> literal(String value) {
            return Optional.ofNullable(value).map(text -> new Expanded(text, Set.of()));
        }

        private boolean isPrefixed(String name) {
            return name.startsWith(SYS_PREFIX) || name.startsWith(ENV_PREFIX);
        }

        private String describe(String ownerKey) {
            return ownerKey == null ? "" : " in the value of " + ownerKey;
        }

        private boolean secret(String key) {
            return registry.isSecret(key);
        }
    }
}
