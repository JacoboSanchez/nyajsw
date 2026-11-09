package io.jsl.nyajsw.config;

/**
 * The value type of a configuration key. The typed configuration layer converts and validates raw
 * values according to it; the description is what the compatibility matrix shows.
 */
public enum KeyType {

    /** Free text, used as it is after interpolation. */
    STRING("text"),

    /** A decimal integer. */
    INTEGER("integer"),

    /** {@code true} or {@code false}. */
    BOOLEAN("boolean"),

    /** A whole number of seconds. */
    DURATION("seconds"),

    /** A number of bytes, optionally with a {@code K}, {@code M} or {@code G} suffix. */
    SIZE("size"),

    /** One of the values listed in {@link KeySpec#values()}, or one of its aliases. */
    ENUM("enumeration"),

    /** A file or directory; relative paths resolve against the configuration file's directory (D13). */
    PATH("path"),

    /** A comma-separated list of names. */
    LIST("list");

    private final String label;

    KeyType(String label) {
        this.label = label;
    }

    /** Short wording for generated documentation. */
    public String label() {
        return label;
    }
}
