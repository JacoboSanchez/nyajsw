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

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Readiness, liveness and the agent as configured (plan §4.7).
 *
 * @param readinessTypes        {@code wrapper.readiness.type}, the probes that apply, all of which must
 *                              pass
 * @param readinessInterval     {@code wrapper.readiness.interval}, at least 1 s
 * @param tcpHost               {@code wrapper.readiness.tcp.host}
 * @param tcpPort               {@code wrapper.readiness.tcp.port}, if set: 1 to 65535
 * @param httpUrl               {@code wrapper.readiness.http.url}, if set: an absolute {@code http} or
 *                              {@code https} URL with a host
 * @param httpStatus            {@code wrapper.readiness.http.status}, e.g. {@code 200-399}
 * @param httpTimeout           {@code wrapper.readiness.http.timeout}, at least 1 s
 * @param logPattern            {@code wrapper.readiness.log.pattern}, if set
 * @param logFile               {@code wrapper.readiness.log.file}; empty means the captured output
 * @param filePath              {@code wrapper.readiness.file.path}, if set; relative to the working
 *                              directory, as the application sees it
 * @param command               {@code wrapper.readiness.command}, if set
 * @param commandTimeout        {@code wrapper.readiness.command.timeout}, if set
 * @param livenessTypes         {@code wrapper.liveness.type}; by default {@code agent} when the agent is
 *                              enabled and implemented, else none (D16; see {@link #agentInEffect})
 * @param livenessInterval      {@code wrapper.liveness.interval}, at least 1 s
 * @param livenessFailures      {@code wrapper.liveness.failures}, at least 1
 * @param livenessAction        {@code wrapper.liveness.action}
 * @param agentEnabled          {@code wrapper.agent.enabled} as configured
 */
public record ProbeConfig(List<String> readinessTypes, Duration readinessInterval, String tcpHost,
        Optional<Integer> tcpPort, Optional<String> httpUrl, StatusRanges httpStatus, Duration httpTimeout,
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

    /** @param workingDir the application's working directory, which relative probe paths start from */
    static ProbeConfig from(ConfigValues values, Path workingDir) {
        boolean agentEnabled = values.required(values.bool(AGENT_ENABLED), AGENT_ENABLED);
        List<String> livenessTypes = values.names(LIVENESS_TYPE, "probe type");
        List<String> readinessTypes = values.names(READINESS_TYPE, "probe type");
        boolean agentInEffect = agentInEffect(agentEnabled);
        return new ProbeConfig(
                readinessTypes.isEmpty() ? values.constantDefault(READINESS_TYPE, ValueParser::parseList)
                        : readinessTypes,
                positive(values, READINESS_INTERVAL),
                values.required(values.string(READINESS_TCP_HOST), READINESS_TCP_HOST),
                port(values),
                httpUrl(values),
                httpStatus(values),
                positive(values, READINESS_HTTP_TIMEOUT),
                values.text(READINESS_LOG_PATTERN),
                values.path(READINESS_LOG_FILE),
                values.path(READINESS_FILE_PATH, workingDir),
                values.text(READINESS_COMMAND),
                values.duration(READINESS_COMMAND_TIMEOUT),
                livenessTypes.isEmpty() ? defaultLivenessTypes(agentInEffect) : livenessTypes,
                positive(values, LIVENESS_INTERVAL),
                atLeastOne(values, LIVENESS_FAILURES),
                values.required(values.enumeration(LIVENESS_ACTION, FailureAction.class), LIVENESS_ACTION),
                agentEnabled);
    }

    /** {@code wrapper.readiness.tcp.port}: a port that cannot exist is a problem and left out. */
    private static Optional<Integer> port(ConfigValues values) {
        Optional<Integer> port = values.integer(READINESS_TCP_PORT);
        if (port.isPresent() && (port.get() < 1 || port.get() > 65535)) {
            values.problem(READINESS_TCP_PORT, values.location(READINESS_TCP_PORT).orElseThrow(),
                    READINESS_TCP_PORT.name() + " must be between 1 and 65535");
            return Optional.empty();
        }
        return port;
    }

    /**
     * {@code wrapper.readiness.http.url}: one that is not an absolute {@code http} or {@code https}
     * URL with a host is a problem and left out; the message does not quote it, since a URL can
     * carry credentials. User information ({@code user:password@}) is warned about: the probe does
     * not send it.
     */
    private static Optional<String> httpUrl(ConfigValues values) {
        Optional<String> url = values.text(READINESS_HTTP_URL);
        if (url.isEmpty()) {
            return url;
        }
        String reason = null;
        try {
            URI uri = new URI(url.get());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                reason = "must start with http:// or https://";
            } else if (uri.getHost() == null) {
                reason = "has no host (a host name may not contain '_')";
            } else if (uri.getRawUserInfo() != null) {
                values.warn(values.location(READINESS_HTTP_URL).orElseThrow(), READINESS_HTTP_URL.name()
                        + " has user information, which the http probe does not send");
            }
        } catch (URISyntaxException e) {
            reason = "is not a valid URL";
        }
        if (reason != null) {
            values.problem(READINESS_HTTP_URL, values.location(READINESS_HTTP_URL).orElseThrow(),
                    READINESS_HTTP_URL.name() + " " + reason);
            return Optional.empty();
        }
        return url;
    }

    /** {@code wrapper.readiness.http.status}; one that cannot be read is a problem, and the default stands in. */
    private static StatusRanges httpStatus(ConfigValues values) {
        String text = values.required(values.string(READINESS_HTTP_STATUS), READINESS_HTTP_STATUS);
        try {
            return StatusRanges.parse(text);
        } catch (IllegalArgumentException e) {
            values.problem(READINESS_HTTP_STATUS, values.location(READINESS_HTTP_STATUS).orElseThrow(),
                    "invalid value '" + values.shown(READINESS_HTTP_STATUS.name(), text) + "' for "
                            + READINESS_HTTP_STATUS.name() + ": " + e.getMessage());
            return values.constantDefault(READINESS_HTTP_STATUS, StatusRanges::parse);
        }
    }

    /**
     * A probe interval or timeout: 0 would check without pause, or give a check no time, so it is a
     * problem, and the default stands in for it so that the other values can still be judged.
     */
    private static Duration positive(ConfigValues values, KeySpec spec) {
        Duration interval = values.required(values.duration(spec), spec);
        if (interval.isPositive()) {
            return interval;
        }
        values.problem(spec, values.location(spec).orElseThrow(), spec.name() + " must be at least 1 s");
        return values.constantDefault(spec, ValueParser::parseDuration);
    }

    /**
     * A count of failed checks: below 1 nothing could trigger the action, so it is a problem; the
     * default stands in.
     */
    private static int atLeastOne(ConfigValues values, KeySpec spec) {
        int count = values.required(values.integer(spec), spec);
        if (count >= 1) {
            return count;
        }
        values.problem(spec, values.location(spec).orElseThrow(), spec.name() + " must be at least 1");
        return values.constantDefault(spec, ValueParser::parseInteger);
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
