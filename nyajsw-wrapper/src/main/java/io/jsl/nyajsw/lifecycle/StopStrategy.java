package io.jsl.nyajsw.lifecycle;

import io.jsl.nyajsw.launch.RunningApplication;
import io.jsl.nyajsw.log.WrapperLog;

import java.time.Duration;
import java.util.Objects;

/**
 * One way of asking the application to end (plan §4.6). The supervisor tries the strategies of
 * {@code wrapper.stop.strategy} in order, each with its share of the stop budget, and ends with
 * {@link TerminateStopStrategy}. Strategies are registered by name in {@link StopStrategyRegistry},
 * so a new one is a new class and a registry entry.
 *
 * <p>A strategy only asks: it returns at once and the supervisor waits for the exit. It runs on the
 * supervisor's thread, so anything that could block (a command, a connection) must be started in
 * the background and bounded by the share it is given. A strategy that cannot help this time (no
 * agent connected, say) answers {@link Result#NOT_APPLICABLE}, and its share passes to the next
 * step; one that throws is logged and treated the same way.
 */
@FunctionalInterface
public interface StopStrategy {

    /** What a strategy did. */
    enum Result {

        /** The application was asked to end; the supervisor waits for the strategy's share. */
        REQUESTED,

        /** The strategy cannot help now; the next step follows at once. */
        NOT_APPLICABLE
    }

    /**
     * What a strategy works with.
     *
     * @param application the invocation to stop
     * @param log         the wrapper log
     * @param share       how long the supervisor waits before the next step; zero for the last step
     */
    record Context(RunningApplication application, WrapperLog log, Duration share) {

        public Context {
            Objects.requireNonNull(application, "application");
            Objects.requireNonNull(log, "log");
            Objects.requireNonNull(share, "share");
        }
    }

    /** Asks the application to end, without waiting for it. */
    Result requestStop(Context context);
}
