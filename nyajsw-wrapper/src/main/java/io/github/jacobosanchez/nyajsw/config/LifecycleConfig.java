package io.github.jacobosanchez.nyajsw.config;

import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.CONTROL;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_PIDFILE;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JVM_EXIT_TIMEOUT;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.MAX_FAILED_INVOCATIONS;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.ON_EXIT;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.ON_EXIT_0;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.ON_EXIT_DEFAULT;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.PIDFILE;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.PING_INTERVAL;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.PING_TIMEOUT;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.RESTART_DELAY;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.SHUTDOWN_TIMEOUT;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.STARTUP_DELAY;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.STARTUP_TIMEOUT;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.STOP_COMMAND;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.STOP_STRATEGY;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.SUCCESSFUL_INVOCATION_TIME;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Supervision as configured (plan §4.6).
 *
 * @param control                  {@code wrapper.control}
 * @param exitActions              action per exit code, from {@code wrapper.on_exit.<code>} and
 *                                 {@code wrapper.on_exit.0}, sorted by code
 * @param defaultExitAction        {@code wrapper.on_exit.default}, for codes not in the map
 * @param restartDelay             {@code wrapper.restart.delay}
 * @param maxFailedInvocations     {@code wrapper.max_failed_invocations}
 * @param successfulInvocationTime {@code wrapper.successful_invocation_time}
 * @param startupDelay             {@code wrapper.startup.delay}
 * @param startupTimeout           {@code wrapper.startup.timeout}
 * @param shutdownTimeout          {@code wrapper.shutdown.timeout}
 * @param jvmExitTimeout           {@code wrapper.jvm_exit.timeout}; zero when not set, since YAJSW
 *                                 documents no default
 * @param pidFile                  {@code wrapper.pidfile}, if set
 * @param javaPidFile              {@code wrapper.java.pidfile}, if set
 * @param stopStrategies           {@code wrapper.stop.strategy}; by default {@code agent,terminate} when
 *                                 the agent is in effect ({@link ProbeConfig#agentInEffect}), else
 *                                 {@code terminate}
 * @param stopCommand              {@code wrapper.stop.command}, if set
 * @param pingInterval             {@code wrapper.ping.interval}, if set
 * @param pingTimeout              {@code wrapper.ping.timeout}, if set
 */
public record LifecycleConfig(ControlMode control, Map<Integer, ExitAction> exitActions, ExitAction defaultExitAction,
        Duration restartDelay, int maxFailedInvocations, Duration successfulInvocationTime, Duration startupDelay,
        Duration startupTimeout, Duration shutdownTimeout, Duration jvmExitTimeout, Optional<Path> pidFile,
        Optional<Path> javaPidFile, List<String> stopStrategies, Optional<String> stopCommand, Optional<Duration> pingInterval,
        Optional<Duration> pingTimeout) {

    public LifecycleConfig {
        Objects.requireNonNull(control, "control");
        exitActions = Collections.unmodifiableMap(new TreeMap<>(exitActions));
        Objects.requireNonNull(defaultExitAction, "defaultExitAction");
        Objects.requireNonNull(restartDelay, "restartDelay");
        Objects.requireNonNull(successfulInvocationTime, "successfulInvocationTime");
        Objects.requireNonNull(startupDelay, "startupDelay");
        Objects.requireNonNull(startupTimeout, "startupTimeout");
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        Objects.requireNonNull(jvmExitTimeout, "jvmExitTimeout");
        Objects.requireNonNull(pidFile, "pidFile");
        Objects.requireNonNull(javaPidFile, "javaPidFile");
        stopStrategies = List.copyOf(stopStrategies);
        Objects.requireNonNull(stopCommand, "stopCommand");
        Objects.requireNonNull(pingInterval, "pingInterval");
        Objects.requireNonNull(pingTimeout, "pingTimeout");
    }

    /**
     * Hides values: free text such as parameters, commands or URLs can contain a secret copied in
     * with <code>${...}</code>. {@link ConfigDump} is the sanctioned way to show values.
     */
    @Override
    public String toString() {
        return "LifecycleConfig[values hidden; see ConfigDump]";
    }

    /**
     * The time a stop may take before the application is terminated: {@code wrapper.shutdown.timeout}
     * plus {@code wrapper.jvm_exit.timeout}, as YAJSW bounds the stop by both together.
     */
    public Duration stopBudget() {
        return shutdownTimeout.plus(jvmExitTimeout);
    }

    /** The action for {@code exitCode}. */
    public ExitAction exitAction(int exitCode) {
        return exitActions.getOrDefault(exitCode, defaultExitAction);
    }

    static LifecycleConfig from(ConfigValues values, boolean agentInEffect) {
        List<String> stopStrategies = values.list(STOP_STRATEGY);
        return new LifecycleConfig(
                values.required(values.enumeration(CONTROL, ControlMode.class), CONTROL),
                exitActions(values),
                values.required(values.enumeration(ON_EXIT_DEFAULT, ExitAction.class), ON_EXIT_DEFAULT),
                values.required(values.duration(RESTART_DELAY), RESTART_DELAY),
                values.required(values.integer(MAX_FAILED_INVOCATIONS), MAX_FAILED_INVOCATIONS),
                values.required(values.duration(SUCCESSFUL_INVOCATION_TIME), SUCCESSFUL_INVOCATION_TIME),
                values.required(values.duration(STARTUP_DELAY), STARTUP_DELAY),
                values.required(values.duration(STARTUP_TIMEOUT), STARTUP_TIMEOUT),
                values.required(values.duration(SHUTDOWN_TIMEOUT), SHUTDOWN_TIMEOUT),
                values.duration(JVM_EXIT_TIMEOUT).orElse(Duration.ZERO),
                values.path(PIDFILE),
                values.path(JAVA_PIDFILE),
                stopStrategies.isEmpty() ? defaultStopStrategies(agentInEffect) : stopStrategies,
                values.text(STOP_COMMAND),
                values.duration(PING_INTERVAL),
                values.duration(PING_TIMEOUT));
    }

    /** The derived default of {@code wrapper.stop.strategy}, as its registry description states. */
    static List<String> defaultStopStrategies(boolean agentInEffect) {
        return agentInEffect ? List.of("agent", "terminate") : List.of("terminate");
    }

    /**
     * Windows exit codes are unsigned 32-bit values that Java reports as {@code int}: 3221225477
     * (0xC0000005) arrives as -1073741819, so codes from 2^31 to 2^32-1 are mapped the same way.
     */
    static BigInteger asExitCode(BigInteger number) {
        boolean unsigned32 = number.signum() > 0 && number.bitLength() == 32;
        return unsigned32 ? BigInteger.valueOf(number.intValue()) : number;
    }

    /**
     * One action per exit code. Codes are normalised first ({@link #asExitCode}), then the last
     * spelling of a code in file order wins: for {@code 7} and {@code 07} the collector has warned;
     * spellings that only agree after normalising ({@code -1} and {@code 4294967295}) warn here.
     * Code 0 comes only from the exact {@code wrapper.on_exit.0}, so another spelling of 0 is ignored
     * with a warning; without it, code 0 takes the default action like any other code. An invalid
     * action (reported by the validator) leaves the code to the default action, and so does a later
     * spelling set to nothing. "Later" is precedence order ({@link ConfigValues#familyInFileOrder}):
     * a system property counts as the latest.
     */
    private static Map<Integer, ExitAction> exitActions(ConfigValues values) {
        Map<Integer, ExitAction> actions = new TreeMap<>();
        Map<Integer, KeyCollector.Member> spelledBy = new HashMap<>();
        for (KeyCollector.Member member : values.familyInFileOrder(ON_EXIT)) {
            BigInteger number = member.numbers().get(0);
            if (number.signum() == 0) {
                values.warn(member.entry().location(), member.key() + " means exit code 0, which "
                        + ON_EXIT_0.name() + " sets; write that key instead. Ignored");
                continue;
            }
            Optional<Integer> code = values.intNumber(ON_EXIT, member, asExitCode(number));
            if (code.isEmpty()) {
                continue;
            }
            KeyCollector.Member earlier = spelledBy.put(code.get(), member);
            if (earlier != null && !earlier.numbers().equals(member.numbers())) {
                values.warn(member.entry().location(), member.key() + " and " + earlier.key()
                        + " mean the same exit code " + code.get() + "; the later one applies");
            }
            if (ValueParser.isUnset(ON_EXIT, member.value())) {
                actions.remove(code.get()); // set to nothing: the default action applies
                continue;
            }
            try {
                ExitAction action = Enum.valueOf(ExitAction.class, ValueParser.parseEnum(ON_EXIT, member.value()));
                actions.put(code.get(), action);
            } catch (IllegalArgumentException e) {
                actions.remove(code.get()); // reported by ConfigValidator; the default action applies
            }
        }
        values.enumeration(ON_EXIT_0, ExitAction.class).ifPresent(action -> actions.put(0, action));
        return actions;
    }
}
