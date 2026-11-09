package io.jsl.nyajsw.config;

import java.util.Objects;

/**
 * A problem that does not stop the configuration from loading but that the operator should see
 * (configuration compatibility rule: unknown or odd input warns, it does not fail).
 *
 * @param location where the problem is
 * @param message  what is wrong, never containing a configuration value
 */
public record ConfigWarning(ConfigLocation location, String message) {

    public ConfigWarning {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(message, "message");
    }

    @Override
    public String toString() {
        return location + ": " + message;
    }
}
