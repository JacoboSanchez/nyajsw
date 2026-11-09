package io.jsl.nyajsw.config;

/**
 * Parts of a YAJSW key declaration that are not yet confirmed against the YAJSW reference or real
 * configuration files (risk R11). They are confirmed, and the marker removed, before the key is
 * implemented; the compatibility matrix shows them as "<em>Verify:</em> name, default".
 */
public enum Unverified {

    /** The key name itself. */
    NAME("name"),

    /** The default value. */
    DEFAULT("default"),

    /** The set of accepted values. */
    VALUES("values"),

    /** The type or the exact meaning of the value. */
    MEANING("meaning");

    private final String label;

    Unverified(String label) {
        this.label = label;
    }

    /** Lower-case wording for generated documentation. */
    public String label() {
        return label;
    }
}
