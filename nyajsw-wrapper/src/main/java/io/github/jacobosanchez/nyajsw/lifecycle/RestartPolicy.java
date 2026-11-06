package io.github.jacobosanchez.nyajsw.lifecycle;

import java.time.Duration;
import java.util.Objects;

/**
 * Counts failed invocations in a row and says when to give up (plan §4.6, as YAJSW documents {@code
 * wrapper.max_failed_invocations} and {@code wrapper.successful_invocation_time}): an invocation
 * that cannot be started, or that ends before it has run for the successful-invocation time, is a
 * failure; one that runs at least that long resets the count. Once the count reaches the maximum
 * there is no further restart.
 *
 * <p>Mutable, and used only by the supervisor's own thread.
 */
final class RestartPolicy {

    private final int maxFailedInvocations;
    private final Duration successfulInvocationTime;
    private int failures;

    /**
     * @param maxFailedInvocations     failures in a row that end supervision; values below 1 count
     *                                 as 1, the minimum YAJSW documents
     * @param successfulInvocationTime the run time from which an invocation counts as successful
     */
    RestartPolicy(int maxFailedInvocations, Duration successfulInvocationTime) {
        this.maxFailedInvocations = Math.max(1, maxFailedInvocations);
        this.successfulInvocationTime = Objects.requireNonNull(successfulInvocationTime, "successfulInvocationTime");
    }

    /** Records an invocation that ended after running for {@code ranFor}. */
    void ended(Duration ranFor) {
        if (ranFor.compareTo(successfulInvocationTime) >= 0) {
            failures = 0;
        } else {
            failures++;
        }
    }

    /** Records an invocation that could not be started. */
    void notStarted() {
        failures++;
    }

    /** Whether another invocation may be started. */
    boolean mayRestart() {
        return failures < maxFailedInvocations;
    }

    /** Failed invocations in a row so far. */
    int failures() {
        return failures;
    }

    /** The limit in effect, at least 1. */
    int maxFailedInvocations() {
        return maxFailedInvocations;
    }
}
