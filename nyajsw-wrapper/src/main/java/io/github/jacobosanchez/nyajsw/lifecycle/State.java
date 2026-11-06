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
     * No application is running and none is due: before the first start, and after an exit whose
     * action is {@code STOP}, which keeps the wrapper alive until it is stopped itself.
     */
    IDLE,

    /** An invocation has been launched and is not ready yet. */
    STARTING,

    /** The application is ready. */
    RUNNING,

    /** The application is being stopped; it exits because the wrapper asked, so no exit action applies. */
    STOPPING,

    /** The application has ended and the next invocation waits for {@code wrapper.restart.delay}. */
    RESTART_WAIT,

    /**
     * Supervision is over as planned and the wrapper may end: the application has ended, or was
     * given up on after it survived termination (the supervisor logs that as an error).
     */
    STOPPED,

    /**
     * Supervision gave up: the application cannot be kept running, or an unexpected error ended
     * supervision, which every other state that is not terminal may move to.
     */
    FAILED;

    /** The states this one may move to (a new, modifiable set on every call). */
    public Set<State> successors() {
        return switch (this) {
            case IDLE -> EnumSet.of(STARTING, STOPPED, FAILED);
            case STARTING -> EnumSet.of(RUNNING, FAILED);
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
