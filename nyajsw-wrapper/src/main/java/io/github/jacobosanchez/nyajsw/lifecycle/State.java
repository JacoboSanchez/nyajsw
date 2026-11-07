package io.github.jacobosanchez.nyajsw.lifecycle;

import java.util.EnumSet;
import java.util.Set;

/**
 * The states of the {@link Supervisor} (plan §4.6) and the transitions allowed between them.
 * {@link #successors()} is the single statement of which moves are legal: the supervisor refuses any
 * other, so a missing case shows up as an error in tests instead of a silently wrong state.
 */
public enum State {

    /**
     * No application is running: before the first start (which may wait for {@code
     * wrapper.startup.delay}), and after an exit whose action is {@code STOP} where {@code
     * wrapper.control} keeps the wrapper alive until it is stopped itself.
     */
    IDLE,

    /**
     * An invocation is being launched or has not passed its readiness probes yet, which must happen
     * within {@code wrapper.startup.timeout}.
     */
    STARTING,

    /** The application is ready. */
    RUNNING,

    /** The application is being stopped; it exits because the wrapper asked, so no exit action applies. */
    STOPPING,

    /**
     * The next invocation waits for {@code wrapper.restart.delay}: the application has ended or could
     * not be started, or it did not become ready and, terminated, is ending first.
     */
    RESTART_WAIT,

    /**
     * Supervision is over as planned and the wrapper may end: the application has ended, or was left
     * running because {@code wrapper.control} lets it outlive the wrapper.
     */
    STOPPED,

    /**
     * Supervision gave up: the application cannot be kept running, survived its termination, or an
     * unexpected error ended supervision, which every other state that is not terminal may move to.
     */
    FAILED;

    /** The states this one may move to (a new, modifiable set on every call). */
    public Set<State> successors() {
        return switch (this) {
            case IDLE -> EnumSet.of(STARTING, STOPPED, FAILED);
            case STARTING -> EnumSet.of(RUNNING, STOPPING, RESTART_WAIT, IDLE, STOPPED, FAILED);
            case RUNNING -> EnumSet.of(STOPPING, RESTART_WAIT, IDLE, STOPPED, FAILED);
            case STOPPING -> EnumSet.of(STOPPED, FAILED);
            case RESTART_WAIT -> EnumSet.of(STARTING, STOPPED, FAILED);
            case STOPPED, FAILED -> EnumSet.noneOf(State.class);
        };
    }

    /** Whether the supervisor may move from this state to {@code target}. */
    public boolean canMoveTo(State target) {
        return successors().contains(target);
    }

    /** Whether supervision is over: no state follows. */
    public boolean isTerminal() {
        return successors().isEmpty();
    }
}
