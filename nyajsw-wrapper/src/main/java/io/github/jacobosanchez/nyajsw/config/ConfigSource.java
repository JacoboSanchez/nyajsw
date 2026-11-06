package io.github.jacobosanchez.nyajsw.config;

/**
 * A source of raw configuration entries: a {@code wrapper.conf} file with its includes today, other
 * layers (such as wrapper JVM system properties) later. Sources only read; interpretation happens in
 * the configuration layer above them.
 */
public interface ConfigSource {

    /** Human-readable name of the source for messages, e.g. the file path. */
    String description();

    /**
     * Reads the source.
     *
     * @return the entries and warnings, never {@code null}
     * @throws ConfigException if the source cannot be read or is malformed beyond what a warning covers
     */
    SourceContent read();
}
