package io.github.jacobosanchez.nyajsw.config;

/**
 * The topic a configuration key belongs to. It groups keys in the compatibility matrix and has no
 * effect on parsing or validation.
 */
public enum KeyCategory {

    CONFIGURATION("Configuration file"),
    JAVA("Java process"),
    LIFECYCLE("Lifecycle"),
    LOGGING("Logging"),
    SERVICE("Windows service"),
    PROBES("Readiness, liveness and agent"),
    OUT_OF_SCOPE("Out of scope");

    private final String title;

    KeyCategory(String title) {
        this.title = title;
    }

    /** Section title for generated documentation. */
    public String title() {
        return title;
    }
}
