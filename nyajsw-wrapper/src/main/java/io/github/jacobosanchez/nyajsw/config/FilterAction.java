package io.github.jacobosanchez.nyajsw.config;

/**
 * What to do when an output filter matches ({@code wrapper.filter.action.<n>}), with the values
 * YAJSW documents. The constant names are the configuration values; {@link ConfigKeys} declares them
 * from this enum, so the spellings exist in one place.
 */
public enum FilterAction {

    /** Restart the application. */
    RESTART,

    /** Stop the application and the wrapper. */
    SHUTDOWN,

    /** Do nothing, and keep filters with higher numbers from acting on the same line. */
    NONE
}
