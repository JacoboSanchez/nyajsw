package io.github.jacobosanchez.nyajsw.config;

/**
 * How the lifetimes of the wrapper and the application are coupled ({@code wrapper.control}, plan
 * §4.6). The constant names are the configuration values; {@link ConfigKeys} declares them from
 * this enum, so the spellings exist in one place.
 */
public enum ControlMode {

    /** Each one ending ends the other. */
    TIGHT,

    /** They are independent. */
    LOOSE,

    /** The wrapper follows the application. */
    APPLICATION,

    /** The application follows the wrapper. */
    WRAPPER
}
