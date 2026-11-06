package io.github.jacobosanchez.nyajsw.config;

/**
 * When Windows starts the service ({@code wrapper.ntservice.starttype}). The constant names are
 * the configuration values; {@link ConfigKeys} declares them from this enum, so the spellings
 * exist in one place.
 */
public enum StartType {

    /** At boot. */
    AUTO_START,

    /** Only when asked. */
    DEMAND_START,

    /** Shortly after boot. */
    DELAYED_AUTO_START,

    /** Never. */
    DISABLED
}
