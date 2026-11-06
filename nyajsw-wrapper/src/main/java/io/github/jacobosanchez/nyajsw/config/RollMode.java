package io.github.jacobosanchez.nyajsw.config;

/**
 * When the wrapper log file rolls over ({@code wrapper.logfile.rollmode}), with the values YAJSW
 * documents. How the modes combine with rolling by size ({@code wrapper.logfile.maxsize}) is not
 * settled yet (P4.3). The constant names are the configuration values; {@link ConfigKeys} declares
 * them from this enum, so the spellings exist in one place.
 */
public enum RollMode {

    /** When the wrapper starts and before each restarted application JVM. */
    JVM,

    /** When the wrapper starts; YAJSW names this mode without describing it (plan P1.10). */
    WRAPPER,

    /** When the date in the log entries changes; the file name must contain {@code YYYYMMDD}. */
    DATE
}
