package io.github.jacobosanchez.nyajsw.config;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The merged, interpolated configuration as {@link ConfigLoader} produced it: one entry per key with
 * the layer it came from, before typed conversion and validation (P1.6).
 *
 * <p>Entry order: file entries at the position of their winning (last) definition, in file order;
 * system-property overrides take the place of the entry they override, and those for keys the file
 * does not set follow in name order; constant defaults of keys nobody set come last, in registry
 * order. {@link KeyCollector} keeps this order for families with free placeholders.
 *
 * <p>Entries of the {@link ConfigLayer#DEFAULT} layer were not written by anyone: checks that judge
 * what the operator configured (unknown, planned, unsupported or fatal keys) must skip them.
 *
 * <p>{@link #toString()} shows keys and layers only, never values.
 *
 * @param file     the main configuration file, absolute
 * @param entries  the effective entries, one per key, in the order above, unmodifiable
 * @param layers   the layer of each entry's value, by key, unmodifiable
 * @param warnings everything worth telling the operator so far, in the order raised, unmodifiable
 */
public record LoadedConfig(Path file, List<ConfigEntry> entries, Map<String, ConfigLayer> layers,
        List<ConfigWarning> warnings) {

    public LoadedConfig {
        Objects.requireNonNull(file, "file");
        entries = List.copyOf(entries);
        layers = Map.copyOf(layers);
        warnings = List.copyOf(warnings);
        Set<String> keys = new HashSet<>();
        for (ConfigEntry entry : entries) {
            if (!keys.add(entry.key())) {
                throw new IllegalArgumentException("key occurs twice: " + entry.key());
            }
        }
        if (!keys.equals(layers.keySet())) {
            throw new IllegalArgumentException("entries and layers name different keys");
        }
    }

    /** The effective entry for {@code key}, if any layer sets it. */
    public Optional<ConfigEntry> get(String key) {
        Objects.requireNonNull(key, "key");
        for (ConfigEntry entry : entries) {
            if (entry.key().equals(key)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** The layer the value of {@code key} comes from, if any layer sets it. */
    public Optional<ConfigLayer> layer(String key) {
        return Optional.ofNullable(layers.get(Objects.requireNonNull(key, "key")));
    }

    @Override
    public String toString() {
        Map<String, ConfigLayer> ordered = new LinkedHashMap<>();
        entries.forEach(entry -> ordered.put(entry.key(), layers.get(entry.key())));
        return "LoadedConfig[file=" + file + ", keys=" + ordered + ", warnings=" + warnings.size() + "]";
    }
}
