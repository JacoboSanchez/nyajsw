package io.github.jacobosanchez.nyajsw.config;

import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.AGENT_ENABLED;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LIVENESS_ACTION;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LIVENESS_FAILURES;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LIVENESS_INTERVAL;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LIVENESS_TYPE;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.PING_INTERVAL;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.PING_TIMEOUT;
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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
 * @param httpUrlShown          {@code httpUrl} with secrets masked, for messages: masked before the
 *                              probe cuts it to scheme, host, port and path, since a cut inside a
 *                              secret (at a {@code ?} or {@code #} it holds) would leave its start
 * @param httpStatus            {@code wrapper.readiness.http.status}, e.g. {@code 200-399}
 * @param httpTimeout           {@code wrapper.readiness.http.timeout}, at least 1 s
 * @param logPattern            {@code wrapper.readiness.log.pattern}, if set: a valid regular expression
 * @param logFile               {@code wrapper.readiness.log.file}, relative to the working directory;
 *                              empty means the captured output
 * @param filePath              {@code wrapper.readiness.file.path}, if set; relative to the working
 *                              directory, as the application sees it
 * @param command               {@code wrapper.readiness.command} split into the program and its
 *                              arguments ({@link ValueParser#parseCommand}), a program with a
 *                              directory resolved against {@code workingDir}; empty when not set
 * @param commandTimeout        {@code wrapper.readiness.command.timeout}, at least 1 s
 * @param workingDir            the application's working directory, where the command runs
 * @param livenessTypes         {@code wrapper.liveness.type}; by default {@code agent} when the agent is
 *                              in effect, else none (D16; see {@link #agentInEffect()})
 * @param livenessInterval      {@code wrapper.liveness.interval}, at least 1 s; when the file does not
 *                              write it, {@code wrapper.ping.interval} if it does and is not 0 (D16)
 * @param livenessFailures      {@code wrapper.liveness.failures}, at least 1; when the file does not
 *                              write it and the agent is the only liveness probe, derived from
 *                              {@code wrapper.ping.timeout} if that is written (D16)
 * @param livenessAction        {@code wrapper.liveness.action}
 * @param agentEnabled          {@code wrapper.agent.enabled} as configured
 * @param agentTimeout          how long the {@code agent} probe waits for an answer: {@code
 *                              wrapper.ping.timeout}, or the liveness interval when that is shorter
 *                              and the timeout was written; empty when the timeout is 0 (Tanuki's
 *                              "never time out"), so that only a lost session fails
 * @param jvmApplication        whether the application is a JVM the agent can run in: no {@code
 *                              wrapper.image}, which may start any executable
 */
public record ProbeConfig(List<String> readinessTypes, Duration readinessInterval, String tcpHost,
        Optional<Integer> tcpPort, Optional<String> httpUrl, Optional<String> httpUrlShown, StatusRanges httpStatus,
        Duration httpTimeout,
        Optional<Pattern> logPattern, Optional<Path> logFile, Optional<Path> filePath, List<String> command,
        Duration commandTimeout, Path workingDir, List<String> livenessTypes, Duration livenessInterval,
        int livenessFailures, FailureAction livenessAction, boolean agentEnabled, Optional<Duration> agentTimeout,
        boolean jvmApplication) {

    public ProbeConfig {
        readinessTypes = List.copyOf(readinessTypes);
        Objects.requireNonNull(readinessInterval, "readinessInterval");
        Objects.requireNonNull(tcpHost, "tcpHost");
        Objects.requireNonNull(tcpPort, "tcpPort");
        Objects.requireNonNull(httpUrl, "httpUrl");
        Objects.requireNonNull(httpUrlShown, "httpUrlShown");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(httpTimeout, "httpTimeout");
        Objects.requireNonNull(logPattern, "logPattern");
        Objects.requireNonNull(logFile, "logFile");
        Objects.requireNonNull(filePath, "filePath");
        command = List.copyOf(command);
        Objects.requireNonNull(commandTimeout, "commandTimeout");
        Objects.requireNonNull(workingDir, "workingDir");
        livenessTypes = List.copyOf(livenessTypes);
        Objects.requireNonNull(livenessInterval, "livenessInterval");
        Objects.requireNonNull(livenessAction, "livenessAction");
        Objects.requireNonNull(agentTimeout, "agentTimeout");
    }

    /**
     * Hides values: free text such as parameters, commands or URLs can contain a secret copied in
     * with <code>${...}</code>. {@link ConfigDump} is the sanctioned way to show values.
     */
    @Override
    public String toString() {
        return "ProbeConfig[values hidden; see ConfigDump]";
    }

    /** @param java the application: its working directory, which relative probe paths start from, and its image */
    static ProbeConfig from(ConfigValues values, JavaConfig java) {
        Path workingDir = java.workingDir();
        // wrapper.image changes what is started only once it takes effect; until then it is a JVM.
        boolean jvmApplication = java.image().isEmpty() || !ConfigKeys.IMAGE.status().takesEffect();
        boolean agentEnabled = values.required(values.bool(AGENT_ENABLED), AGENT_ENABLED);
        List<String> livenessTypes = values.names(LIVENESS_TYPE, "probe type");
        List<String> readinessTypes = values.names(READINESS_TYPE, "probe type");
        boolean agentInEffect = agentInEffect(agentEnabled, jvmApplication);
        // 0 for either ping key is Tanuki's "no heartbeat": the agent is not checked by default then.
        boolean pingOff = writtenZero(values, PING_TIMEOUT) || writtenZero(values, PING_INTERVAL);
        List<String> liveness = livenessTypes.isEmpty() ? defaultLivenessTypes(agentInEffect && !pingOff)
                : livenessTypes;
        Duration interval = livenessInterval(values);
        Ping ping = ping(values, liveness, interval);
        Optional<String> url = httpUrl(values);
        return new ProbeConfig(
                readinessTypes.isEmpty() ? values.constantDefault(READINESS_TYPE, ValueParser::parseList)
                        : readinessTypes,
                positive(values, READINESS_INTERVAL),
                values.required(values.string(READINESS_TCP_HOST), READINESS_TCP_HOST),
                port(values),
                url,
                url.map(values.masker()::maskText),
                httpStatus(values),
                positive(values, READINESS_HTTP_TIMEOUT),
                logPattern(values),
                values.path(READINESS_LOG_FILE, workingDir),
                values.path(READINESS_FILE_PATH, workingDir),
                command(values, workingDir),
                positive(values, READINESS_COMMAND_TIMEOUT),
                workingDir,
                liveness,
                interval,
                ping.failures(),
                values.required(values.enumeration(LIVENESS_ACTION, FailureAction.class), LIVENESS_ACTION),
                agentEnabled,
                ping.timeout(),
                jvmApplication);
    }

    /** What the agent probe works with: how long each ping may take, and the failures in a row that act. */
    private record Ping(Optional<Duration> timeout, int failures) {
    }

    /**
     * YAJSW's {@code wrapper.ping.timeout} is the silence after which the JVM counts as hung (D16).
     * Written, and with the agent as the only liveness probe and {@code wrapper.liveness.failures}
     * not written, it becomes pings that may each take up to one interval, as many failed in a row
     * as fit into the timeout; so a hang is acted on after about the timeout, as before. Otherwise
     * each ping may take the whole timeout, and {@code wrapper.liveness.failures} counts them.
     */
    private static Ping ping(ConfigValues values, List<String> liveness, Duration interval) {
        Duration timeout = values.required(values.duration(PING_TIMEOUT), PING_TIMEOUT);
        if (timeout.isZero()) {
            return new Ping(Optional.empty(), atLeastOne(values, LIVENESS_FAILURES));
        }
        boolean agentOnly = liveness.size() == 1 && liveness.get(0).strip().equalsIgnoreCase("agent");
        if (values.writtenAt(PING_TIMEOUT).isPresent() && values.writtenAt(LIVENESS_FAILURES).isEmpty() && agentOnly) {
            Duration perPing = timeout.compareTo(interval) < 0 ? timeout : interval;
            // A failing ping takes up to perPing, then the next waits an interval.
            long cycle = perPing.plus(interval).toMillis();
            int failures = (int) Math.max(1, Math.min(Integer.MAX_VALUE, (timeout.toMillis() + cycle - 1) / cycle));
            return new Ping(Optional.of(perPing), failures);
        }
        return new Ping(Optional.of(timeout), atLeastOne(values, LIVENESS_FAILURES));
    }

    /** Whether the file writes {@code spec} as 0. */
    private static boolean writtenZero(ConfigValues values, KeySpec spec) {
        return values.writtenAt(spec).isPresent() && values.duration(spec).map(Duration::isZero).orElse(false);
    }

    /**
     * {@code wrapper.liveness.interval}, or YAJSW's {@code wrapper.ping.interval} when the file
     * writes only that, and not as 0 (D16): both have constant defaults, so what counts is which one
     * is written.
     */
    private static Duration livenessInterval(ConfigValues values) {
        Optional<ConfigLocation> liveness = values.writtenAt(LIVENESS_INTERVAL);
        Optional<ConfigLocation> ping = values.writtenAt(PING_INTERVAL);
        if (liveness.isEmpty() && ping.isPresent() && !writtenZero(values, PING_INTERVAL)) {
            return positive(values, PING_INTERVAL);
        }
        if (liveness.isPresent() && ping.isPresent()) {
            values.warn(ping.get(), PING_INTERVAL.name() + " is ignored: " + LIVENESS_INTERVAL.name()
                    + " sets the liveness interval");
        }
        return positive(values, LIVENESS_INTERVAL);
    }

    /**
     * {@code wrapper.readiness.log.pattern} compiled; one that is not a valid regular expression is
     * a problem and left out. The message gives the reason, not the whole pattern; the reason may
     * quote a name from it (an unknown group or property).
     */
    private static Optional<Pattern> logPattern(ConfigValues values) {
        Optional<String> pattern = values.text(READINESS_LOG_PATTERN);
        if (pattern.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Pattern.compile(pattern.get()));
        } catch (PatternSyntaxException e) {
            values.problem(READINESS_LOG_PATTERN, values.location(READINESS_LOG_PATTERN).orElseThrow(),
                    READINESS_LOG_PATTERN.name() + " is not a valid regular expression: " + e.getDescription());
            return Optional.empty();
        }
    }

    /**
     * {@code wrapper.readiness.command} as program and arguments. The program follows the rule of
     * the other executable keys ({@link JavaConfig#isBareName}): a bare name is looked up on {@code
     * PATH}, anything with a directory is resolved against the working directory, since Windows
     * would look for it from the wrapper's own. One with an open double quote, without a program or
     * with a program that is not a valid path is a problem and left out, not quoted, since a
     * command can carry a secret.
     */
    private static List<String> command(ConfigValues values, Path workingDir) {
        Optional<String> command = values.text(READINESS_COMMAND);
        if (command.isEmpty()) {
            return List.of();
        }
        String problem;
        try {
            List<String> arguments = new ArrayList<>(ValueParser.parseCommand(command.get()));
            if (arguments.isEmpty() || arguments.get(0).isBlank()) {
                problem = "names no program";
            } else {
                String program = ValueParser.normalisePath(arguments.get(0));
                arguments.set(0, JavaConfig.isBareName(program) ? program
                        : workingDir.resolve(program).normalize().toString());
                return List.copyOf(arguments);
            }
        } catch (InvalidPathException e) {
            problem = "the program is not a valid path";
        } catch (IllegalArgumentException e) {
            problem = e.getMessage();
        }
        values.problem(READINESS_COMMAND, values.location(READINESS_COMMAND).orElseThrow(),
                READINESS_COMMAND.name() + ": " + problem);
        return List.of();
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
     * Whether the agent is injected into the application and the derived defaults count on it
     * ({@link #agentInEffect(boolean, boolean)}).
     */
    public boolean agentInEffect() {
        return agentInEffect(agentEnabled, jvmApplication);
    }

    /**
     * Whether the agent is injected and the derived defaults count on it: it is enabled and
     * implemented, and the application is a JVM. One predicate for both, so that no default asks
     * for an agent that is never injected.
     */
    static boolean agentInEffect(boolean agentEnabled, boolean jvmApplication) {
        return agentEnabled && jvmApplication && AGENT_ENABLED.status().takesEffect();
    }

    /** The derived default of {@code wrapper.liveness.type}, as its registry description states (D16). */
    static List<String> defaultLivenessTypes(boolean agentInEffect) {
        return agentInEffect ? List.of("agent") : List.of();
    }
}
