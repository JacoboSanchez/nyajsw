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
    WRAPPER;

    /**
     * Whether the wrapper ends once the application has ended: {@link #TIGHT} and {@link #WRAPPER}.
     * Decides whether the exit action {@code STOP} keeps the wrapper running (D20).
     */
    public boolean wrapperEndsWithApplication() {
        return this == TIGHT || this == WRAPPER;
    }

    /**
     * Whether the application is stopped when the wrapper ends: {@link #TIGHT} and {@link
     * #APPLICATION}. Otherwise a stop of the wrapper leaves the application running.
     */
    public boolean applicationEndsWithWrapper() {
        return this == TIGHT || this == APPLICATION;
    }
}
