package io.github.jacobosanchez.nyajsw.config;

/**
 * How far nyajsw supports a configuration key (plan §3.1). The status decides what happens when a
 * configuration file contains the key: supported keys take effect, every other status produces a
 * warning naming the key and its status, and {@link #UNSUPPORTED_FATAL} fails validation because
 * ignoring the key would silently change behaviour.
 */
public enum KeyStatus {

    /** The key is implemented and takes effect. */
    SUPPORTED("supported"),

    /** The key will be implemented in the phase recorded in {@link KeySpec#phase()}; warned until then. */
    PLANNED("planned"),

    /** The key is accepted and deliberately has no effect, with a warning. */
    IGNORED("ignored"),

    /** The key is not supported; it is warned about and has no effect. */
    UNSUPPORTED("unsupported"),

    /** The key is not supported and its presence fails validation. */
    UNSUPPORTED_FATAL("unsupported, fatal when present");

    private final String label;

    KeyStatus(String label) {
        this.label = label;
    }

    /**
     * Whether a key with this status takes effect, so that a problem with its value must stop the
     * wrapper (an error) rather than only be pointed out (a warning).
     */
    public boolean takesEffect() {
        return this == SUPPORTED;
    }

    /** Lower-case wording for warnings and generated documentation. */
    public String label() {
        return label;
    }
}
