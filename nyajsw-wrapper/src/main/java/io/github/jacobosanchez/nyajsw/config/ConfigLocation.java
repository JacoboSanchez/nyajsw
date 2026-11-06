package io.github.jacobosanchez.nyajsw.config;

import java.util.Objects;

/**
 * Where a configuration entry, warning or error comes from, so messages can point the operator at
 * the exact file and line.
 *
 * @param source a file path or another human-readable source name
 * @param line   1-based line number, or {@code 0} when the source has no lines
 */
public record ConfigLocation(String source, int line) {

    public ConfigLocation {
        Objects.requireNonNull(source, "source");
        if (line < 0) {
            throw new IllegalArgumentException("line must be >= 0: " + line);
        }
    }

    /** A location that names a whole source rather than one line of it. */
    public static ConfigLocation of(String source) {
        return new ConfigLocation(source, 0);
    }

    /** {@code source:line}, or just {@code source} when there is no line. */
    @Override
    public String toString() {
        return line == 0 ? source : source + ":" + line;
    }
}
