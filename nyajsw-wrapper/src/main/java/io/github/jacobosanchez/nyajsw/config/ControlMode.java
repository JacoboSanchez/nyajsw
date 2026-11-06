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

    /** The application ends when the wrapper ends; the wrapper survives the application. */
    APPLICATION,

    /** The wrapper ends when the application ends; the application survives the wrapper. */
    WRAPPER
}
