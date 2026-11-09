package io.jsl.nyajsw.lifecycle;

import io.jsl.nyajsw.config.ControlMode;
import io.jsl.nyajsw.config.ExitAction;
import io.jsl.nyajsw.config.LifecycleConfig;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link LifecycleConfig}s for tests, with the YAJSW defaults for everything a test does not set,
 * so that a new component of the record is added here once.
 */
final class Lifecycles {

    static final Duration RESTART_DELAY = Duration.ofSeconds(5);
    static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    static final Duration SUCCESSFUL_TIME = Duration.ofSeconds(60);
    static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);
    static final int MAX_FAILED = 5;

    private Lifecycles() {
    }

    static LifecycleConfig lifecycle(ControlMode control, ExitAction defaultAction, Map<Integer, ExitAction> actions,
            int maxFailedInvocations, Duration startupDelay, Duration startupTimeout, Duration jvmExitTimeout,
            List<String> stopStrategies) {
        return lifecycle(control, defaultAction, actions, maxFailedInvocations, startupDelay, startupTimeout,
                SHUTDOWN_TIMEOUT, jvmExitTimeout, stopStrategies);
    }

    /** The defaults with {@code wrapper.shutdown.timeout=0} and no {@code wrapper.jvm_exit.timeout}. */
    static LifecycleConfig withoutStopBudget() {
        return lifecycle(ControlMode.TIGHT, ExitAction.SHUTDOWN, Map.of(), MAX_FAILED, Duration.ZERO,
                STARTUP_TIMEOUT, Duration.ZERO, Duration.ZERO, List.of(TerminateStopStrategy.NAME));
    }

    private static LifecycleConfig lifecycle(ControlMode control, ExitAction defaultAction,
            Map<Integer, ExitAction> actions, int maxFailedInvocations, Duration startupDelay, Duration startupTimeout,
            Duration shutdownTimeout, Duration jvmExitTimeout, List<String> stopStrategies) {
        return new LifecycleConfig(control, actions, defaultAction, RESTART_DELAY, maxFailedInvocations,
                SUCCESSFUL_TIME, startupDelay, startupTimeout, shutdownTimeout, jvmExitTimeout, Optional.empty(),
                Optional.empty(), stopStrategies, List.of(), Path.of(""));
    }
}
