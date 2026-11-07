package io.github.jacobosanchez.nyajsw.platform.windows;

import io.github.jacobosanchez.nyajsw.platform.ServiceException;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Waits for a service to reach a state by polling its status, as the Service Control Manager
 * offers no notification a client can block on without a callback thread. The polling interval
 * follows the service's wait hint (a tenth of it, between 100 ms and 1 s), and time is measured on a
 * monotonic ticker, so tests replace both the ticker and the pause.
 */
final class StateWait {

    /** Waits between two polls; tests do not wait. */
    @FunctionalInterface
    interface Pause {
        void pause(Duration duration) throws InterruptedException;
    }

    static final Duration SHORTEST_POLL = Duration.ofMillis(100);
    static final Duration LONGEST_POLL = Duration.ofSeconds(1);

    static final StateWait REAL = new StateWait(System::nanoTime, duration -> Thread.sleep(duration.toMillis()));

    private final LongSupplier nanoTicker;
    private final Pause pause;

    StateWait(LongSupplier nanoTicker, Pause pause) {
        this.nanoTicker = Objects.requireNonNull(nanoTicker, "nanoTicker");
        this.pause = Objects.requireNonNull(pause, "pause");
    }

    /**
     * Polls {@code query} until the service is in {@code target}.
     *
     * @param giveUp a state that ends the wait as a failure (the service stopped while starting), or
     *               0 for none
     * @return the status that reached {@code target}
     * @throws ServiceException when {@code giveUp} is reached, when {@code timeout} passes first, or
     *                          when the thread is interrupted; the message gives the reason only, for
     *                          the caller to say what it attempted
     */
    ScmStatus until(Supplier<ScmStatus> query, int target, int giveUp, Duration timeout) {
        long deadline = nanoTicker.getAsLong() + nanos(timeout); // may overflow; only differences are used
        for (;;) {
            ScmStatus status = query.get();
            if (status.currentState() == target) {
                return status;
            }
            if (giveUp != 0 && status.currentState() == giveUp) {
                throw new ServiceException("the service stopped again" + exitCodes(status), 0);
            }
            long remaining = deadline - nanoTicker.getAsLong();
            if (remaining <= 0) {
                throw new ServiceException("the service is still " + ServiceStates.name(status.currentState())
                        + " after " + shown(timeout), 0);
            }
            try {
                Duration interval = interval(status.waitHint());
                pause.pause(interval.toNanos() > remaining ? Duration.ofNanos(remaining) : interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceException("interrupted while waiting for the service", 0, e);
            }
        }
    }

    private static long nanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE; // beyond 292 years: no limit, in practice
        }
    }

    /** {@code timeout} for messages: whole seconds, or milliseconds below a second or between them. */
    static String shown(Duration timeout) {
        return timeout.toMillis() % 1000 == 0 ? timeout.toSeconds() + " s" : timeout.toMillis() + " ms";
    }

    static Duration interval(int waitHintMillis) {
        Duration tenth = Duration.ofMillis(Math.max(0, waitHintMillis) / 10);
        return tenth.compareTo(SHORTEST_POLL) < 0 ? SHORTEST_POLL
                : tenth.compareTo(LONGEST_POLL) > 0 ? LONGEST_POLL : tenth;
    }

    /** Why a stopped service stopped, as its status says; empty when it says nothing. */
    private static String exitCodes(ScmStatus status) {
        if (status.win32ExitCode() == Win32Errors.SERVICE_SPECIFIC_ERROR) {
            return " (its own exit code " + status.serviceSpecificExitCode() + "; see the wrapper log)";
        }
        if (status.win32ExitCode() != 0) {
            return " (" + Win32Errors.describe(status.win32ExitCode(), "") + ")";
        }
        return "; see the wrapper log";
    }
}
