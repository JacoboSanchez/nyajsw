package io.github.jacobosanchez.nyajsw.config;

/**
 * When the wrapper log file rolls over ({@code wrapper.logfile.rollmode}). The constant names are
 * the configuration values; {@link ConfigKeys} declares them from this enum, so the spellings
 * exist in one place.
 */
public enum RollMode {

    /** Never. */
    NONE,

    /** When it reaches {@code wrapper.logfile.maxsize}. */
    SIZE,

    /** When the date changes. */
    DATE,

    /** On either condition. */
    SIZE_OR_DATE
}
