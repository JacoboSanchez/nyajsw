package io.github.jacobosanchez.nyajsw.config;

import io.github.jacobosanchez.nyajsw.NyajswException;

import java.util.Optional;

/**
 * The configuration cannot be loaded or is invalid. The message starts with the location when one
 * is known, e.g. {@code C:\app\conf\wrapper.conf:12: included file not found: ...}.
 */
public class ConfigException extends NyajswException {

    private static final long serialVersionUID = 1L;

    private final transient ConfigLocation location;

    public ConfigException(String message) {
        this(null, message, null);
    }

    public ConfigException(ConfigLocation location, String message) {
        this(location, message, null);
    }

    public ConfigException(ConfigLocation location, String message, Throwable cause) {
        super(location == null ? message : location + ": " + message, cause);
        this.location = location;
    }

    /** The file and line the problem was found at, when known. */
    public Optional<ConfigLocation> location() {
        return Optional.ofNullable(location);
    }
}
