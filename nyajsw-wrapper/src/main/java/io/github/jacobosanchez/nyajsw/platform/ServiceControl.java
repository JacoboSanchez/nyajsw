package io.github.jacobosanchez.nyajsw.platform;

/** Why the service manager asks a running service to stop. */
public enum ServiceControl {
    /** An operator or program stopped the service. */
    STOP,
    /** The system is shutting down; the service has the pre-shutdown timeout set at install to stop. */
    PRESHUTDOWN,
    /**
     * The system is shutting down and only seconds are left; a running service that accepts {@link
     * #PRESHUTDOWN} has normally been asked to stop by then already.
     */
    SHUTDOWN
}
