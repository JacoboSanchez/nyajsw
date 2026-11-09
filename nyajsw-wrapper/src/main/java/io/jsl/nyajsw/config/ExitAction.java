package io.jsl.nyajsw.config;

/**
 * What the wrapper does when the application exits (plan §4.6, {@code wrapper.on_exit.*}). The
 * constant names are the configuration values; {@link ConfigKeys} declares them from this enum, so
 * the spellings exist in one place.
 */
public enum ExitAction {

    /** Start the application again, subject to the restart policy. */
    RESTART,

    /** Stop the application and the wrapper (the service stops). */
    SHUTDOWN,

    /** Stop the application but keep the wrapper running. */
    STOP
}
