package io.github.jacobosanchez.nyajsw.lifecycle;

import java.time.Duration;

/**
 * Runs a task after a delay. The {@link Supervisor} takes every timed decision through it (restart
 * delay, shutdown timeout), so tests drive time with a fake instead of sleeping.
 */
@FunctionalInterface
public interface Scheduler {

    /** A scheduled task that has not necessarily run yet. */
    @FunctionalInterface
    interface Timer {

        /**
         * Keeps the task from running if it has not started yet, so a real scheduler does not hold
         * timers that no longer matter; doing nothing is allowed, since the supervisor also ignores
         * timers it has replaced.
         */
        void cancel();
    }

    /**
     * Runs {@code task} once, on some thread, no earlier than {@code delay} from now. The task must
     * be quick; the supervisor's tasks only queue an event.
     *
     * @return a handle to cancel the task
     */
    Timer schedule(Duration delay, Runnable task);
}
