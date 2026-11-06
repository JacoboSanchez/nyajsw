package io.github.jacobosanchez.nyajsw.config;

import java.util.Objects;

/**
 * One {@code key=value} pair exactly as a {@link ConfigSource} read it: key and value are trimmed but
 * not interpreted (no interpolation, no backslash processing, no type conversion).
 *
 * <p>{@link #toString()} omits the value because it may be a password; only the configuration layer,
 * which knows which keys are secret, decides how values are shown.
 *
 * @param key      the key, never empty
 * @param value    the raw value, possibly empty
 * @param location the line the entry starts on
 */
public record ConfigEntry(String key, String value, ConfigLocation location) {

    public ConfigEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(location, "location");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("key must not be empty");
        }
    }

    @Override
    public String toString() {
        return "ConfigEntry[key=" + key + ", location=" + location + "]";
    }
}
