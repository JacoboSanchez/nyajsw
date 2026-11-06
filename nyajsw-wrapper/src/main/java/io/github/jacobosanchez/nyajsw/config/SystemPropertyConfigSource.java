package io.github.jacobosanchez.nyajsw.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The wrapper JVM's system properties as configuration entries, the highest layer (plan §4.5,
 * AGENTS.md §4.6): YAJSW lets {@code -Dname=value} on the wrapper command line override the file
 * (D17). {@link ConfigLoader} uses every property to override a key the file sets under the same name,
 * and adds keys the file does not set only for {@link #isConfigurationKey configuration keys}, so
 * JVM properties such as {@code java.home} do not become configuration entries.
 *
 * <p>Entries come in key order, so the result does not depend on the property map's iteration order.
 * Values are trimmed like file values. Their location names the property, e.g.
 * {@code system property wrapper.tmp.path}.
 */
public final class SystemPropertyConfigSource implements ConfigSource {

    private static final String PREFIX = "wrapper.";

    private final Map<String, String> systemProperties;

    public SystemPropertyConfigSource(Map<String, String> systemProperties) {
        this.systemProperties = Map.copyOf(Objects.requireNonNull(systemProperties, "systemProperties"));
    }

    /** Whether a system property adds a configuration key even when the file does not set it. */
    public static boolean isConfigurationKey(String name) {
        return name.startsWith(PREFIX) && name.length() > PREFIX.length();
    }

    @Override
    public String description() {
        return "wrapper JVM system properties";
    }

    @Override
    public SourceContent read() {
        List<ConfigEntry> entries = systemProperties.entrySet().stream()
                .filter(property -> !property.getKey().isEmpty())
                .sorted(Map.Entry.comparingByKey())
                .map(property -> new ConfigEntry(property.getKey(), FileConfigSource.trim(property.getValue()),
                        ConfigLocation.of("system property " + property.getKey())))
                .toList();
        return new SourceContent(entries, List.of());
    }
}
