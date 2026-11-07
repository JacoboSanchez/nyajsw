package io.github.jacobosanchez.nyajsw.platform;

/**
 * The state of an installed service as the operating system reports it: the states of the Windows
 * Service Control Manager, which other service managers can map to.
 */
public enum ServiceState {
    STOPPED,
    START_PENDING,
    STOP_PENDING,
    RUNNING,
    CONTINUE_PENDING,
    PAUSE_PENDING,
    PAUSED
}
