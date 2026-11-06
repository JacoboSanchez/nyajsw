package io.github.jacobosanchez.nyajsw.config;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads a {@code wrapper.conf}: reads the layers, merges them into one entry per key and resolves
 * <code>${...}</code> variables (plan §4.5). Precedence, highest first (AGENTS.md §4.6): wrapper JVM
 * system properties, then the configuration file with its includes, then the constant defaults of
 * the key registry. A system property overrides a key of the file whatever its name; it adds a key
 * the file does not set only when it is named {@code wrapper.*} (see {@link SystemPropertyConfigSource}).
 *
 * <p>Within the file, a key set more than once takes its last value, with a warning naming both
 * places; whether YAJSW does the same is unverified (P1.9). Typed conversion and validation follow in
 * P1.6.
 */
public final class ConfigLoader {

    private static final ConfigLocation DEFAULT_LOCATION = ConfigLocation.of("built-in default");

    private final Map<String, String> systemProperties;
    private final KeyRegistry registry;
    private final Interpolator interpolator;

    /**
     * @param systemProperties           the wrapper JVM's system properties: the override layer and
     *                                   <code>${sys:...}</code> variables
     * @param environment                environment variables for <code>${...}</code>
     * @param caseInsensitiveEnvironment whether environment names are case-insensitive (Windows)
     * @param registry                   the key registry: defaults and secret keys
     */
    public ConfigLoader(Map<String, String> systemProperties, Map<String, String> environment,
            boolean caseInsensitiveEnvironment, KeyRegistry registry) {
        this.systemProperties = Map.copyOf(systemProperties);
        this.registry = Objects.requireNonNull(registry, "registry");
        this.interpolator = new Interpolator(this.systemProperties, environment, caseInsensitiveEnvironment, registry);
    }

    /** A loader over this JVM's system properties and environment, with the nyajsw key registry. */
    public static ConfigLoader forCurrentProcess() {
        return new ConfigLoader(Interpolator.currentSystemProperties(), System.getenv(),
                Interpolator.currentEnvironmentIgnoresCase(), ConfigKeys.registry());
    }

    /**
     * @param file the main configuration file
     * @throws ConfigException if a file cannot be read, an include fails, or interpolation fails
     */
    public LoadedConfig load(Path file) {
        Path absolute = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Map<String, ConfigEntry> defaults = defaults();
        SourceContent propertyContent = new SystemPropertyConfigSource(systemProperties).read();
        Map<String, ConfigEntry> properties = byKey(propertyContent.entries());
        FileConfigSource.Layers around = new FileConfigSource.Layers(properties, defaults);
        SourceContent content = readFile(absolute, around, properties);
        List<ConfigWarning> warnings = new ArrayList<>(propertyContent.warnings());
        warnings.addAll(content.warnings());

        Map<String, ConfigEntry> merged = new LinkedHashMap<>();
        Map<String, ConfigLayer> layers = new LinkedHashMap<>();
        for (ConfigEntry entry : content.entries()) {
            ConfigEntry earlier = merged.remove(entry.key()); // re-inserted at its winning position
            if (earlier != null) {
                warnings.add(new ConfigWarning(entry.location(), entry.key() + " is set again; this value replaces"
                        + " the one at " + earlier.location()
                        + (properties.containsKey(entry.key()) ? ", and a system property overrides both" : "")));
            }
            merged.put(entry.key(), entry);
            layers.put(entry.key(), ConfigLayer.FILE);
        }
        properties.forEach((key, entry) -> {
            // Any property overrides a key the file sets; only wrapper.* properties add new keys.
            if (merged.containsKey(key) || SystemPropertyConfigSource.isConfigurationKey(key)) {
                merged.put(key, entry); // keeps the position of an overridden file entry
                layers.put(key, ConfigLayer.SYSTEM_PROPERTY);
            }
        });
        defaults.forEach((key, entry) -> {
            if (merged.putIfAbsent(key, entry) == null) {
                layers.put(key, ConfigLayer.DEFAULT);
            }
        });

        Interpolator.Result result = interpolator.interpolate(List.copyOf(merged.values()));
        warnings.addAll(result.warnings());
        return new LoadedConfig(absolute, result.entries(), layers, warnings, result.externalSecrets());
    }

    /**
     * Reads the file with the encoding {@code wrapper.conf.encoding} declares (D7, P1.9).
     *
     * <ul>
     *   <li>A system property wins: its charset is used for every file; set to nothing, it forces
     *       the default detection whatever the file declares.</li>
     *   <li>Otherwise the file is first probed with the default detection (leniently, so an include
     *       path that only decodes correctly under the declared charset does not fail). Key names
     *       are ASCII, so the probe finds the declaration; the last one wins. The file and its
     *       includes are then read strictly with that charset, which therefore must encode ASCII
     *       like ASCII (UTF-16 or EBCDIC must be given as a system property), and the result must
     *       still declare the same charset.</li>
     *   <li>Variables are not resolved in the value.</li>
     * </ul>
     */
    private SourceContent readFile(Path file, FileConfigSource.Layers layers, Map<String, ConfigEntry> properties) {
        String key = ConfigKeys.CONF_ENCODING.name();
        ConfigEntry property = properties.get(key);
        if (property != null) {
            if (property.value().isEmpty()) {
                return new FileConfigSource(file, interpolator, layers).read();
            }
            return new FileConfigSource(file, interpolator, layers,
                    new FileConfigSource.Encoding(charset(property), property.location().toString())).read();
        }
        ConfigEntry declared = lastEntry(new FileConfigSource(file, interpolator, layers).probe(), key);
        if (declared == null || declared.value().isEmpty()) {
            return new FileConfigSource(file, interpolator, layers).read();
        }
        Charset charset = charset(declared);
        if (!encodesAsciiAsAscii(charset)) {
            throw new ConfigException(declared.location(), charset.name() + " cannot be declared inside the"
                    + " configuration file, which must be readable before the declaration is known; start the"
                    + " wrapper with -D" + key + "=" + declared.value() + " instead");
        }
        SourceContent content = new FileConfigSource(file, interpolator, layers,
                new FileConfigSource.Encoding(charset, declared.location().toString())).read();
        ConfigEntry confirmed = lastEntry(content.entries(), key);
        if (confirmed == null || confirmed.value().isEmpty() || !charset(confirmed).equals(charset)) {
            ConfigLocation where = confirmed != null ? confirmed.location() : declared.location();
            throw new ConfigException(where, "read as " + charset.name() + " (declared at " + declared.location()
                    + "), the configuration declares " + (confirmed == null ? "no encoding"
                    : "'" + confirmed.value() + "'") + " for " + key + "; make the declarations agree");
        }
        return content;
    }

    private static ConfigEntry lastEntry(List<ConfigEntry> entries, String key) {
        ConfigEntry last = null;
        for (ConfigEntry entry : entries) {
            if (entry.key().equals(key)) {
                last = entry;
            }
        }
        return last;
    }

    /** Whether printable ASCII and line breaks encode to the same bytes as in ASCII. */
    private static boolean encodesAsciiAsAscii(Charset charset) {
        StringBuilder ascii = new StringBuilder("\t\r\n");
        for (char c = ' '; c <= '~'; c++) {
            ascii.append(c);
        }
        String text = ascii.toString();
        return charset.canEncode() && Arrays.equals(text.getBytes(charset), text.getBytes(StandardCharsets.US_ASCII));
    }

    private static Charset charset(ConfigEntry entry) {
        if (entry.value().contains("${")) {
            throw new ConfigException(entry.location(), "variables are not resolved in " + entry.key()
                    + "; write the encoding name itself");
        }
        try {
            return Charset.forName(entry.value());
        } catch (IllegalArgumentException e) { // IllegalCharsetNameException, UnsupportedCharsetException
            throw new ConfigException(entry.location(), "unknown encoding '" + entry.value() + "' for "
                    + entry.key() + "; use a Java charset name such as UTF-8 or windows-1252");
        }
    }

    /** Constant defaults of exact keys, in registry order; derived defaults belong to the typed views. */
    private Map<String, ConfigEntry> defaults() {
        Map<String, ConfigEntry> defaults = new LinkedHashMap<>();
        for (KeySpec spec : registry.specs()) {
            if (spec.pattern().isExact() && spec.defaultValue() instanceof KeyDefault.Constant constant) {
                defaults.put(spec.name(), new ConfigEntry(spec.name(), constant.value(), DEFAULT_LOCATION));
            }
        }
        return defaults;
    }

    private static Map<String, ConfigEntry> byKey(List<ConfigEntry> entries) {
        Map<String, ConfigEntry> map = new LinkedHashMap<>();
        entries.forEach(entry -> map.put(entry.key(), entry));
        return map;
    }
}
