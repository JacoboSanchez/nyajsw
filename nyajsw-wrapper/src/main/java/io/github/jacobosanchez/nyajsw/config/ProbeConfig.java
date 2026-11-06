package io.github.jacobosanchez.nyajsw.config;

import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.AGENT_ENABLED;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LIVENESS_ACTION;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LIVENESS_FAILURES;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LIVENESS_INTERVAL;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LIVENESS_TYPE;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.READINESS_COMMAND;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.READINESS_COMMAND_TIMEOUT;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.READINESS_FILE_PATH;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.READINESS_HTTP_STATUS;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.READINESS_HTTP_TIMEOUT;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.READINESS_HTTP_URL;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.READINESS_INTERVAL;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.READINESS_LOG_FILE;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.READINESS_LOG_PATTERN;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.READINESS_TCP_HOST;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.READINESS_TCP_PORT;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.READINESS_TYPE;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Readiness, liveness and the agent as configured (plan §4.7).
 *
 * @param readinessTypes        {@code wrapper.readiness.type}; all must pass
 * @param readinessInterval     {@code wrapper.readiness.interval}
 * @param tcpHost               {@code wrapper.readiness.tcp.host}
 * @param tcpPort               {@code wrapper.readiness.tcp.port}, if set
 * @param httpUrl               {@code wrapper.readiness.http.url}, if set
 * @param httpStatus            {@code wrapper.readiness.http.status}, e.g. {@code 200-399}
 * @param httpTimeout           {@code wrapper.readiness.http.timeout}, if set
 * @param logPattern            {@code wrapper.readiness.log.pattern}, if set
 * @param logFile               {@code wrapper.readiness.log.file}; empty means the captured output
 * @param filePath              {@code wrapper.readiness.file.path}, if set
 * @param command               {@code wrapper.readiness.command}, if set
 * @param commandTimeout        {@code wrapper.readiness.command.timeout}, if set
 * @param livenessTypes         {@code wrapper.liveness.type}; by default {@code agent} when the agent is
 *                              enabled and implemented, else none (D16; see {@link #agentInEffect})
 * @param livenessInterval      {@code wrapper.liveness.interval}
 * @param livenessFailures      {@code wrapper.liveness.failures}
 * @param livenessAction        {@code wrapper.liveness.action}
 * @param agentEnabled          {@code wrapper.agent.enabled} as configured
 */
public record ProbeConfig(List<String> readinessTypes, Duration readinessInterval, String tcpHost,
        Optional<Integer> tcpPort, Optional<String> httpUrl, String httpStatus, Optional<Duration> httpTimeout,
        Optional<String> logPattern, Optional<Path> logFile, Optional<Path> filePath, Optional<String> command,
        Optional<Duration> commandTimeout, List<String> livenessTypes, Duration livenessInterval,
        int livenessFailures, FailureAction livenessAction, boolean agentEnabled) {

    public ProbeConfig {
        readinessTypes = List.copyOf(readinessTypes);
        Objects.requireNonNull(readinessInterval, "readinessInterval");
        Objects.requireNonNull(tcpHost, "tcpHost");
        Objects.requireNonNull(tcpPort, "tcpPort");
        Objects.requireNonNull(httpUrl, "httpUrl");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(httpTimeout, "httpTimeout");
        Objects.requireNonNull(logPattern, "logPattern");
        Objects.requireNonNull(logFile, "logFile");
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(commandTimeout, "commandTimeout");
        livenessTypes = List.copyOf(livenessTypes);
        Objects.requireNonNull(livenessInterval, "livenessInterval");
        Objects.requireNonNull(livenessAction, "livenessAction");
    }

    /**
     * Hides values: free text such as parameters, commands or URLs can contain a secret copied in
     * with <code>${...}</code>. {@link ConfigDump} is the sanctioned way to show values.
     */
    @Override
    public String toString() {
        return "ProbeConfig[values hidden; see ConfigDump]";
    }

    static ProbeConfig from(ConfigValues values) {
        boolean agentEnabled = values.required(values.bool(AGENT_ENABLED), AGENT_ENABLED);
        List<String> livenessTypes = values.list(LIVENESS_TYPE);
        boolean agentInEffect = agentInEffect(agentEnabled);
        return new ProbeConfig(
                values.list(READINESS_TYPE),
                values.required(values.duration(READINESS_INTERVAL), READINESS_INTERVAL),
                values.required(values.string(READINESS_TCP_HOST), READINESS_TCP_HOST),
                values.integer(READINESS_TCP_PORT),
                values.text(READINESS_HTTP_URL),
                values.required(values.string(READINESS_HTTP_STATUS), READINESS_HTTP_STATUS),
                values.duration(READINESS_HTTP_TIMEOUT),
                values.text(READINESS_LOG_PATTERN),
                values.path(READINESS_LOG_FILE),
                values.path(READINESS_FILE_PATH),
                values.text(READINESS_COMMAND),
                values.duration(READINESS_COMMAND_TIMEOUT),
                livenessTypes.isEmpty() ? defaultLivenessTypes(agentInEffect) : livenessTypes,
                values.required(values.duration(LIVENESS_INTERVAL), LIVENESS_INTERVAL),
                values.required(values.integer(LIVENESS_FAILURES), LIVENESS_FAILURES),
                values.required(values.enumeration(LIVENESS_ACTION, FailureAction.class), LIVENESS_ACTION),
                agentEnabled);
    }

    /**
     * Whether derived defaults may count on the agent: it is enabled and implemented. While
     * {@code wrapper.agent.enabled} is only planned it has no effect, like every planned key, so the
     * defaults behave as without the agent until P7 makes it supported.
     */
    static boolean agentInEffect(boolean agentEnabled) {
        return agentEnabled && AGENT_ENABLED.status() == KeyStatus.SUPPORTED;
    }

    /** The derived default of {@code wrapper.liveness.type}, as its registry description states (D16). */
    static List<String> defaultLivenessTypes(boolean agentInEffect) {
        return agentInEffect ? List.of("agent") : List.of();
    }
}
