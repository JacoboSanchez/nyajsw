package io.jsl.nyajsw.config;

/**
 * What to do when a check keeps failing ({@code wrapper.liveness.action}). The constant names are
 * the configuration values; {@link ConfigKeys} declares them from this enum, so the spellings exist
 * in one place.
 */
public enum FailureAction {

    /** Restart the application. */
    RESTART,

    /** Stop the application and the wrapper. */
    SHUTDOWN
}
