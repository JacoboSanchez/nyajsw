package io.github.jacobosanchez.nyajsw.config;

/**
 * Wrapper log levels with their YAJSW names, from least to most verbose (plan §4.8). The constant
 * names are the configuration values; {@link ConfigKeys} declares them from this enum, so the
 * spellings exist in one place.
 */
public enum LogLevel {

    /** Nothing is logged. */
    NONE,

    /** Only failures that stop the wrapper. */
    FATAL,

    /** Errors. */
    ERROR,

    /** Lifecycle changes. */
    STATUS,

    /** Normal operation, including application output. */
    INFO,

    /** Diagnostics. */
    DEBUG
}
