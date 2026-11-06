package io.github.jacobosanchez.nyajsw.config;

import java.nio.file.Path;
import java.util.ArrayList;
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
        SourceContent content = new FileConfigSource(absolute, interpolator,
                new FileConfigSource.Layers(properties, defaults)).read();
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
        return new LoadedConfig(absolute, result.entries(), layers, warnings);
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
