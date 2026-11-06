package io.github.jacobosanchez.nyajsw.lifecycle;

import io.github.jacobosanchez.nyajsw.config.ControlMode;
import io.github.jacobosanchez.nyajsw.config.ExitAction;
import io.github.jacobosanchez.nyajsw.config.LifecycleConfig;

import java.util.Objects;

/**
 * Decides what the wrapper does after the application exited by itself (plan §4.6): the configured
 * {@code wrapper.on_exit.*} action, read together with {@code wrapper.control}.
 *
 * <p>{@code STOP} means "stop the application but not the wrapper", as YAJSW documents it, but
 * under {@link ControlMode#TIGHT TIGHT} and {@link ControlMode#WRAPPER WRAPPER} the wrapper ends
 * with the application, so there {@code STOP} ends the wrapper as {@code SHUTDOWN} does (D20).
 */
final class ExitActionResolver {

    /** What follows an exit. */
    enum AfterExit {

        /** Start the next invocation, if the restart policy allows. */
        RESTART,

        /** End supervision; the wrapper ends too. */
        END,

        /** Keep the wrapper running without an application until it is stopped. */
        WAIT
    }

    /**
     * A decision with what led to it.
     *
     * @param action the configured action for the exit code
     * @param next   what follows
     */
    record Decision(ExitAction action, AfterExit next) {
    }

    private final LifecycleConfig lifecycle;

    ExitActionResolver(LifecycleConfig lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    Decision resolve(int exitCode) {
        ExitAction action = lifecycle.exitAction(exitCode);
        AfterExit next = switch (action) {
            case RESTART -> AfterExit.RESTART;
            case SHUTDOWN -> AfterExit.END;
            case STOP -> lifecycle.control().wrapperEndsWithApplication() ? AfterExit.END : AfterExit.WAIT;
        };
        return new Decision(action, next);
    }
}
