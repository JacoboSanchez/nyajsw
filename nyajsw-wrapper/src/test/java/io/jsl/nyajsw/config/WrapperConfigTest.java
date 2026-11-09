package io.jsl.nyajsw.config;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class WrapperConfigTest {

    private Path dir;

    @BeforeMethod
    public void createDir() throws IOException {
        dir = Files.createTempDirectory("nyajsw-typed-test").toRealPath();
    }

    @AfterMethod(alwaysRun = true)
    public void deleteDir() throws IOException {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    @Test
    public void typicalConfigurationIsTyped() throws IOException {
        WrapperConfig config = load(
                "app_home=" + dir.toString().replace('\\', '/'),
                "wrapper.java.command=${app_home}/jre/bin/java",
                "wrapper.java.app.mainclass=com.example.server.Main",
                "wrapper.java.classpath.2=lib/*.jar",
                "wrapper.java.classpath.1=conf",
                "wrapper.java.additional.1=-Xss1m",
                "wrapper.java.additional.10=-Dlast=true",
                "wrapper.java.additional.2=-Dmiddle=true",
                "wrapper.java.library.path.1=native",
                "wrapper.java.library.path.2=" + dir.resolve("abs").toString().replace('\\', '/'),
                "wrapper.java.app.jar=lib/../lib/app.jar",
                "wrapper.java.initmemory=256",
                "wrapper.java.maxmemory=1024",
                "wrapper.app.parameter.1=--port",
                "wrapper.app.parameter.2=8080",
                "wrapper.app.env.APP_MODE=prod",
                "wrapper.working.dir=work",
                "wrapper.ntservice.displayname=Example Server",
                "wrapper.ntservice.starttype=DELAY_START",
                "wrapper.ntservice.dependency.1=Tcpip",
                "wrapper.ntservice.account=.\\\\svc-example",
                "wrapper.ntservice.password=s3cret",
                "wrapper.startup.timeout=90",
                "wrapper.on_exit.default=SHUTDOWN",
                "wrapper.on_exit.12=RESTART",
                "wrapper.logfile=logs/wrapper.log",
                "wrapper.logfile.maxsize=10m",
                "wrapper.logfile.loglevel=info",
                "wrapper.readiness.type=tcp, http",
                "wrapper.readiness.tcp.port=8080");

        JavaConfig java = config.java();
        assertEquals(java.command(), Optional.of(dir.resolve("jre/bin/java").toString()));
        assertEquals(java.mainClass(), Optional.of("com.example.server.Main"));
        assertEquals(java.classpath(), List.of("conf", "lib/*.jar"));
        assertEquals(java.jvmOptions(), List.of("-Xss1m", "-Dmiddle=true", "-Dlast=true"));
        // Application-side paths resolve against the working directory; absolute ones stay.
        assertEquals(java.libraryPath(), List.of(dir.resolve("work/native"), dir.resolve("abs")));
        assertEquals(java.jar(), Optional.of(dir.resolve("work/lib/app.jar")));
        assertEquals(java.initMemoryMb(), Optional.of(256));
        assertEquals(java.maxMemoryMb(), Optional.of(1024));
        assertEquals(java.parameters(), List.of("--port", "8080"));
        assertEquals(java.environment(), Map.of("APP_MODE", "prod"));
        assertEquals(java.workingDir(), dir.resolve("work"));
        assertFalse(java.javaw());

        ServiceConfig service = config.service();
        assertEquals(service.name(), Optional.of("Main"));
        assertEquals(service.displayName(), Optional.of("Example Server"));
        assertEquals(service.startType(), StartType.DELAYED_AUTO_START);
        assertEquals(service.dependencies(), List.of("Tcpip"));
        assertEquals(service.account(), Optional.of(".\\svc-example"));
        assertEquals(service.password().orElseThrow().reveal(), "s3cret");

        LifecycleConfig lifecycle = config.lifecycle();
        assertEquals(lifecycle.startupTimeout(), Duration.ofSeconds(90));
        assertEquals(lifecycle.exitAction(12), ExitAction.RESTART);
        assertEquals(lifecycle.exitAction(1), ExitAction.SHUTDOWN);
        assertEquals(lifecycle.exitAction(0), ExitAction.SHUTDOWN);

        LogConfig log = config.log();
        assertEquals(log.logfile(), Optional.of(dir.resolve("logs/wrapper.log")));
        assertEquals(log.maxSizeBytes(), Optional.of(10L * 1024 * 1024));
        assertEquals(log.logfileLevel(), Optional.of(LogLevel.INFO));

        assertEquals(config.probes().readinessTypes(), List.of("tcp", "http"));
        assertEquals(config.probes().tcpPort(), Optional.of(8080));
        assertEquals(config.file(), dir.resolve("wrapper.conf"));
    }

    @Test
    public void defaultsAndDerivedDefaultsApply() throws IOException {
        WrapperConfig config = load("wrapper.java.app.mainclass=Outer$Inner");

        assertEquals(config.java().workingDir(), dir);
        assertEquals(config.java().command(), Optional.empty());
        assertEquals(config.service().name(), Optional.of("Inner"));
        assertEquals(config.service().startType(), StartType.AUTO_START);
        assertEquals(config.service().account(), Optional.empty());
        assertEquals(config.service().password(), Optional.empty());
        LifecycleConfig lifecycle = config.lifecycle();
        assertEquals(lifecycle.control(), ControlMode.TIGHT);
        assertEquals(lifecycle.defaultExitAction(), ExitAction.SHUTDOWN);
        assertEquals(lifecycle.exitActions(), Map.of());
        assertEquals(lifecycle.exitAction(0), ExitAction.SHUTDOWN);
        assertEquals(lifecycle.maxFailedInvocations(), 5);
        assertEquals(lifecycle.successfulInvocationTime(), Duration.ofSeconds(60));
        assertEquals(lifecycle.startupDelay(), Duration.ZERO);
        assertEquals(lifecycle.shutdownTimeout(), Duration.ofSeconds(30));
        assertEquals(lifecycle.jvmExitTimeout(), Duration.ZERO);
        assertEquals(lifecycle.stopBudget(), Duration.ofSeconds(30));
        assertEquals(lifecycle.restartDelay(), Duration.ofSeconds(5));
        // The agent is enabled by default (D10), so the defaults use it.
        assertEquals(lifecycle.stopStrategies(), List.of("agent", "terminate"));
        ProbeConfig probes = config.probes();
        assertTrue(probes.agentEnabled());
        assertEquals(probes.readinessTypes(), List.of("process"));
        assertEquals(probes.readinessInterval(), Duration.ofSeconds(1));
        assertEquals(probes.tcpHost(), "127.0.0.1");
        assertEquals(probes.httpStatus(), StatusRanges.parse("200-399"));
        assertEquals(probes.httpTimeout(), Duration.ofSeconds(5));
        assertEquals(probes.livenessTypes(), List.of("agent"));
        assertEquals(probes.livenessInterval(), Duration.ofSeconds(10));
        assertEquals(probes.livenessFailures(), 3);
        assertEquals(probes.livenessAction(), FailureAction.RESTART);
        assertFalse(config.log().debug());
    }

    @Test
    public void emptyTextValuesMeanNotSetSoDerivedDefaultsApply() throws IOException {
        WrapperConfig config = load("wrapper.java.app.mainclass=a.Main", "wrapper.ntservice.name=",
                "wrapper.ntservice.displayname=", "wrapper.stop.command=");

        assertEquals(config.service().name(), Optional.of("Main"));
        assertEquals(config.service().displayName(), Optional.empty());
        assertEquals(config.lifecycle().stopCommand(), List.of());
        assertEquals(load("wrapper.java.app.mainclass=").service().name(), Optional.empty());
    }

    @Test
    public void javaCommandIsABareNameOrAResolvedPath() throws IOException {
        assertEquals(load("wrapper.java.command=java").java().command(), Optional.of("java"));
        assertEquals(load("wrapper.working.dir=w", "wrapper.java.command=jre/bin/java").java().command(),
                Optional.of(dir.resolve("w/jre/bin/java").toString()));
        assertEquals(load("wrapper.java.command=").java().command(), Optional.empty());
    }

    @Test
    public void invalidPathIsReportedWithSecretsHiddenAndControlCharactersEscaped() throws IOException {
        WrapperConfig config = loadWith(Map.of("wrapper.image", "/x/${wrapper.ntservice.password}\0y"),
                "wrapper.ntservice.password=Pa55w0rd");

        assertEquals(config.java().image(), Optional.empty());
        String message = messages(config).stream().filter(m -> m.startsWith("invalid path")).findFirst().orElseThrow();
        assertFalse(message.contains("Pa55w0rd"), message);
        assertTrue(message.contains("/x/****\\u0000y"), message);
    }

    @Test
    public void byDefaultTheAgentIsInEffectAndTheDefaultsUseIt() throws IOException {
        WrapperConfig config = load("wrapper.java.app.mainclass=Main");

        assertTrue(config.probes().agentInEffect());
        assertEquals(config.lifecycle().stopStrategies(), List.of("agent", "terminate"));
        assertEquals(config.probes().livenessTypes(), List.of("agent"));
        assertEquals(config.probes().agentTimeout(), Optional.of(Duration.ofSeconds(30)), "wrapper.ping.timeout");
        assertEquals(config.probes().livenessFailures(), 3);
    }

    @Test
    public void withoutTheAgentTheDefaultsDoWithoutIt() throws IOException {
        WrapperConfig config = load("wrapper.agent.enabled=false");

        assertFalse(config.probes().agentInEffect());
        assertFalse(config.probes().agentEnabled());
        assertEquals(config.lifecycle().stopStrategies(), List.of("terminate"));
        assertEquals(config.probes().livenessTypes(), List.of());
        // wrapper.image, an executable that may be no JVM, turns the agent off once it takes effect.
        assertEquals(load("wrapper.image=bin/server.exe").probes().agentInEffect(),
                !ConfigKeys.IMAGE.status().takesEffect());
        assertTrue(ProbeConfig.agentInEffect(true, true));
        assertFalse(ProbeConfig.agentInEffect(true, false));
        assertFalse(ProbeConfig.agentInEffect(false, true));
    }

    @Test
    public void yajswPingSettingsMapOntoTheAgentLivenessProbe() throws IOException {
        // A written timeout is the silence that counts as a hang: pings of up to one interval, as many
        // failed in a row as fit (7 s + 7 s per failed check, so 1 for 12 s; 3 for 40 s).
        ProbeConfig ping = load("wrapper.ping.interval=7", "wrapper.ping.timeout=12").probes();
        assertEquals(ping.livenessInterval(), Duration.ofSeconds(7));
        assertEquals(ping.agentTimeout(), Optional.of(Duration.ofSeconds(7)));
        assertEquals(ping.livenessFailures(), 1);
        ProbeConfig longer = load("wrapper.ping.interval=7", "wrapper.ping.timeout=40").probes();
        assertEquals(longer.livenessFailures(), 3);
        ProbeConfig shortTimeout = load("wrapper.ping.interval=30", "wrapper.ping.timeout=5").probes();
        assertEquals(shortTimeout.agentTimeout(), Optional.of(Duration.ofSeconds(5)), "never longer than written");
        ProbeConfig counted = load("wrapper.ping.timeout=12", "wrapper.liveness.failures=5").probes();
        assertEquals(counted.agentTimeout(), Optional.of(Duration.ofSeconds(12)), "the failures are counted");
        assertEquals(counted.livenessFailures(), 5);

        WrapperConfig both = load("wrapper.ping.interval=7", "wrapper.liveness.interval=20");
        assertEquals(both.probes().livenessInterval(), Duration.ofSeconds(20), "the nyajsw key wins");
        assertTrue(messages(both).contains("wrapper.ping.interval is ignored: wrapper.liveness.interval sets the"
                + " liveness interval"), messages(both).toString());
        assertEquals(load().probes().livenessInterval(), Duration.ofSeconds(10), "neither: the liveness default");
    }

    @Test
    public void aPingKeyOfZeroTurnsTheDefaultHeartbeatOff() throws IOException {
        // Tanuki's "never time out", which YAJSW files inherit: no error, and no agent liveness by default.
        for (String zero : List.of("wrapper.ping.timeout=0", "wrapper.ping.interval=0")) {
            ProbeConfig probes = load(zero).probes();
            assertEquals(probes.livenessTypes(), List.of(), zero);
            assertEquals(probes.livenessInterval(), Duration.ofSeconds(10), zero);
        }
        assertEquals(load("wrapper.ping.timeout=0").probes().agentTimeout(), Optional.empty(), "no pings");
        assertEquals(load("wrapper.ping.timeout=0", "wrapper.liveness.type=agent").probes().livenessTypes(),
                List.of("agent"), "an explicit agent probe then only fails on a lost session");
        assertEquals(load("wrapper.agent.enabled=false", "wrapper.ping.timeout=0").probes().livenessTypes(), List.of());
    }

    @Test
    public void explicitListsReplaceDerivedDefaults() throws IOException {
        WrapperConfig config = load("wrapper.stop.strategy=command, terminate", "wrapper.liveness.type=http",
                "wrapper.stop.strategy.unused=x");

        assertEquals(config.lifecycle().stopStrategies(), List.of("command", "terminate"));
        assertEquals(config.probes().livenessTypes(), List.of("http"));
    }

    @Test
    public void exitCodeZeroComesFromItsOwnKey() throws IOException {
        WrapperConfig config = load("wrapper.on_exit.0=RESTART", "wrapper.on_exit.00=STOP", "wrapper.on_exit.-0=STOP",
                "wrapper.on_exit.-1=STOP", "wrapper.on_exit.7=STOP", "wrapper.on_exit.07=RESTART",
                "wrapper.on_exit.3221225477=SHUTDOWN");

        assertEquals(config.lifecycle().exitActions(), Map.of(-1073741819, ExitAction.SHUTDOWN, -1, ExitAction.STOP,
                0, ExitAction.RESTART, 7, ExitAction.RESTART));
        assertEquals(config.lifecycle().exitAction(5), config.lifecycle().defaultExitAction());
        assertTrue(messages(config).contains("wrapper.on_exit.-0 means exit code 0, which wrapper.on_exit.0 sets;"
                + " write that key instead. Ignored"), messages(config).toString());
        List<String> messages = messages(config);
        assertTrue(messages.contains("wrapper.on_exit.00 means exit code 0, which wrapper.on_exit.0 sets; write that"
                + " key instead. Ignored"), messages.toString());
        assertTrue(messages.stream().anyMatch(m -> m.startsWith("wrapper.on_exit.07 has the same index as")),
                messages.toString());
    }

    @Test
    public void anExitCodeOutOfRangeOrAnInvalidActionIsAnErrorNowThatExitActionsTakeEffect() {
        ConfigException error = expectThrows(ConfigException.class,
                () -> load("wrapper.on_exit.99999999999=STOP", "wrapper.on_exit.5=SOMETIMES"));

        List<String> problems = error.problems().stream().map(Throwable::getMessage).toList();
        assertTrue(problems.stream().anyMatch(p -> p.contains("wrapper.on_exit.99999999999: number out of range")),
                problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("invalid value 'SOMETIMES' for wrapper.on_exit.5")),
                problems.toString());
    }

    @Test
    public void exitCodeSpellingsThatAgreeOnlyAfterNormalisingFollowFileOrder() throws IOException {
        WrapperConfig unsignedFirst = load("wrapper.on_exit.4294967295=STOP", "wrapper.on_exit.-1=SHUTDOWN");
        WrapperConfig signedFirst = load("wrapper.on_exit.-1=SHUTDOWN", "wrapper.on_exit.4294967295=STOP");

        assertEquals(unsignedFirst.lifecycle().exitAction(-1), ExitAction.SHUTDOWN);
        assertEquals(signedFirst.lifecycle().exitAction(-1), ExitAction.STOP);
        assertTrue(messages(unsignedFirst).contains("wrapper.on_exit.-1 and wrapper.on_exit.4294967295 mean the same"
                + " exit code -1; the later one applies"), messages(unsignedFirst).toString());
    }

    @Test
    public void systemPropertySpellingOfAnExitCodeWinsOverTheFile() throws IOException {
        WrapperConfig config = loadWith(Map.of("wrapper.on_exit.4294967295", "RESTART"),
                "wrapper.on_exit.4294967295=STOP", "wrapper.on_exit.-1=SHUTDOWN");
        WrapperConfig padded = loadWith(Map.of("wrapper.on_exit.7", "RESTART"),
                "wrapper.on_exit.7=STOP", "wrapper.on_exit.07=SHUTDOWN");

        assertEquals(config.lifecycle().exitAction(-1), ExitAction.RESTART);
        assertEquals(padded.lifecycle().exitAction(7), ExitAction.RESTART);
        assertTrue(messages(padded).stream().noneMatch(m -> m.contains("mean the same exit code")),
                messages(padded).toString());
    }

    @Test
    public void laterEmptyExitActionLeavesTheCodeToTheDefault() throws IOException {
        WrapperConfig config = load("wrapper.on_exit.default=SHUTDOWN", "wrapper.on_exit.7=STOP", "wrapper.on_exit.07=");

        assertEquals(config.lifecycle().exitAction(7), ExitAction.SHUTDOWN);
    }

    @Test
    public void laterInvalidExitActionIsAnError() {
        ConfigException error = expectThrows(ConfigException.class, () -> load("wrapper.on_exit.default=SHUTDOWN",
                "wrapper.on_exit.7=STOP", "wrapper.on_exit.07=NOPE"));

        assertTrue(error.getMessage().contains("invalid value 'NOPE' for wrapper.on_exit.07"), error.getMessage());
    }

    @Test
    public void invalidValueOfAPlannedKeyFallsBackToTheDefaultWithWarning() throws IOException {
        WrapperConfig config = load("wrapper.filter.action.1=SOMETIMES");

        assertTrue(messages(config).stream().anyMatch(m -> m.startsWith("invalid value 'SOMETIMES'")),
                messages(config).toString());
    }

    @Test
    public void readinessAndLivenessSettingsTakeEffect() throws IOException {
        ProbeConfig probes = load("wrapper.readiness.type=process", "wrapper.readiness.interval=2",
                "wrapper.liveness.type=process", "wrapper.liveness.interval=7", "wrapper.liveness.failures=1",
                "wrapper.liveness.action=shutdown").probes();

        assertEquals(probes.readinessTypes(), List.of("process"));
        assertEquals(probes.readinessInterval(), Duration.ofSeconds(2));
        assertEquals(probes.livenessTypes(), List.of("process"));
        assertEquals(probes.livenessInterval(), Duration.ofSeconds(7));
        assertEquals(probes.livenessFailures(), 1);
        assertEquals(probes.livenessAction(), FailureAction.SHUTDOWN);
    }

    @Test
    public void probeTypesHoldingASecretAreIgnoredWholeWithAWarning() throws IOException {
        WrapperConfig config = load("wrapper.ntservice.password=Pa5,5w0rd",
                "wrapper.readiness.type=x${wrapper.ntservice.password}",
                "wrapper.liveness.type=process,x${wrapper.ntservice.password}");

        assertEquals(config.probes().readinessTypes(), List.of("process"));
        assertEquals(config.probes().livenessTypes(), List.of("agent"), "the default applies");
        assertTrue(messages(config).contains("wrapper.readiness.type contains a secret, which cannot name a probe type;"
                + " ignored, the default applies"), messages(config).toString());
        assertTrue(messages(config).contains("wrapper.liveness.type contains a secret, which cannot name a probe type;"
                + " ignored, the default applies"), messages(config).toString());
        assertTrue(messages(config).stream().noneMatch(m -> m.contains("5w0rd")), messages(config).toString());
    }

    @Test
    public void theTcpHttpAndFileSettingsAreRead() throws IOException {
        ProbeConfig probes = load("wrapper.working.dir=work", "wrapper.readiness.tcp.host=localhost",
                "wrapper.readiness.tcp.port=8443", "wrapper.readiness.http.url=https://localhost:8443/health",
                "wrapper.readiness.http.status=200, 204", "wrapper.readiness.http.timeout=2",
                "wrapper.readiness.file.path=run/ready").probes();

        assertEquals(probes.tcpHost(), "localhost");
        assertEquals(probes.tcpPort(), Optional.of(8443));
        assertEquals(probes.httpUrl(), Optional.of("https://localhost:8443/health"));
        assertEquals(probes.httpStatus().toString(), "200,204");
        assertEquals(probes.httpTimeout(), Duration.ofSeconds(2));
        assertEquals(probes.filePath(), Optional.of(dir.resolve("work/run/ready")), "the application's working dir");
    }

    @Test
    public void tcpHttpSettingsThatCannotWorkAreErrorsThatDoNotQuoteTheUrl() {
        ConfigException error = expectThrows(ConfigException.class, () -> load("wrapper.readiness.tcp.port=70000",
                "wrapper.readiness.http.url=ftp://user:pw@host/x", "wrapper.readiness.http.status=200-",
                "wrapper.readiness.http.timeout=0"));

        List<String> problems = error.problems().stream().map(ConfigException::getMessage).toList();
        assertEquals(problems.size(), 4, problems.toString());
        assertTrue(problems.stream().anyMatch(m -> m.contains("wrapper.readiness.tcp.port must be between 1 and"
                + " 65535")), problems.toString());
        assertTrue(problems.stream().anyMatch(m -> m.contains("wrapper.readiness.http.url must start with http://"
                + " or https://")), problems.toString());
        assertTrue(problems.stream().anyMatch(m -> m.contains("invalid value '200-' for"
                + " wrapper.readiness.http.status")), problems.toString());
        assertTrue(problems.stream().anyMatch(m -> m.contains("wrapper.readiness.http.timeout must be at least 1 s")),
                problems.toString());
        assertTrue(problems.stream().noneMatch(m -> m.contains("pw@")), problems.toString());
        assertTrue(expectThrows(ConfigException.class, () -> load("wrapper.readiness.http.url=http:///path"))
                .getMessage().contains("wrapper.readiness.http.url has no host (a host name may not contain '_')"));
        assertTrue(expectThrows(ConfigException.class, () -> load("wrapper.readiness.http.url=http://a b/"))
                .getMessage().contains("wrapper.readiness.http.url is not a valid URL"));
        expectThrows(ConfigException.class, () -> load("wrapper.readiness.tcp.port=0"));
    }

    @Test
    public void theProbeCommandIsSplitIntoArgumentsAndRunsInTheWorkingDirectory() throws IOException {
        ProbeConfig probes = load("wrapper.working.dir=work",
                "wrapper.readiness.command=\"C:/Program Files/check.exe\" --port 8080 \"\"").probes();

        assertEquals(probes.command(), List.of(Path.of("C:/Program Files/check.exe").isAbsolute()
                ? Path.of("C:/Program Files/check.exe").toString()
                : dir.resolve("work/C:/Program Files/check.exe").normalize().toString(), "--port", "8080", ""));
        assertEquals(probes.commandTimeout(), Duration.ofSeconds(10));
        assertEquals(probes.workingDir(), dir.resolve("work"));
        assertEquals(load().probes().command(), List.of());
        assertEquals(load("wrapper.working.dir=work", "wrapper.readiness.command=bin/check -v").probes().command(),
                List.of(dir.resolve("work/bin/check").toString(), "-v"), "relative to the working directory");
        assertEquals(load("wrapper.readiness.command=curl -f").probes().command(), List.of("curl", "-f"),
                "a bare name is looked up on PATH");
    }

    @Test
    public void theStopCommandIsSplitIntoArgumentsAndRunsInTheWorkingDirectory() throws IOException {
        LifecycleConfig lifecycle = load("wrapper.working.dir=work", "wrapper.stop.command=bin/stop.bat \"a b\" -q")
                .lifecycle();

        assertEquals(lifecycle.stopCommand(), List.of(dir.resolve("work/bin/stop.bat").toString(), "a b", "-q"));
        assertEquals(lifecycle.workingDir(), dir.resolve("work"));
        assertEquals(lifecycle.stopStrategies(), List.of("command", "agent", "terminate"));
        assertEquals(load("wrapper.stop.command=stop.cmd").lifecycle().stopCommand(), List.of("stop.cmd"),
                "a bare name is looked up on PATH");
        assertEquals(load().lifecycle().stopStrategies(), List.of("agent", "terminate"));
        assertEquals(load("wrapper.stop.command=stop.cmd", "wrapper.stop.strategy=agent").lifecycle()
                .stopStrategies(), List.of("agent"), "a written list is kept");
        ConfigException error = expectThrows(ConfigException.class,
                () -> load("wrapper.stop.command=stop --token \"s3cr3t"));
        assertTrue(error.getMessage().contains("wrapper.stop.command: a double quote is not closed"),
                error.getMessage());
        assertFalse(error.getMessage().contains("s3cr3t"), error.getMessage());
    }

    @Test
    public void aProbeCommandWithAnOpenQuoteOrNoTimeIsAnErrorThatDoesNotQuoteIt() {
        ConfigException error = expectThrows(ConfigException.class, () -> load(
                "wrapper.readiness.command=check --token \"s3cr3t", "wrapper.readiness.command.timeout=0"));

        List<String> problems = error.problems().stream().map(ConfigException::getMessage).toList();
        assertEquals(problems.size(), 2, problems.toString());
        assertTrue(problems.stream().anyMatch(m -> m.contains("wrapper.readiness.command: a double quote is not"
                + " closed")), problems.toString());
        assertTrue(problems.stream().anyMatch(m -> m.contains("wrapper.readiness.command.timeout must be at least"
                + " 1 s")), problems.toString());
        assertTrue(problems.stream().noneMatch(m -> m.contains("s3cr3t")), problems.toString());
        assertTrue(expectThrows(ConfigException.class, () -> load("wrapper.readiness.command=\"\" --x"))
                .getMessage().contains("wrapper.readiness.command: names no program"));
    }

    @Test
    public void theLogProbeSettingsAreReadAndABadPatternIsAnErrorThatDoesNotQuoteIt() throws IOException {
        ProbeConfig probes = load("wrapper.working.dir=work", "wrapper.readiness.log.pattern=Started .* in \\d+",
                "wrapper.readiness.log.file=logs/app.log").probes();

        assertEquals(probes.logPattern().orElseThrow().pattern(), "Started .* in \\d+");
        assertEquals(probes.logFile(), Optional.of(dir.resolve("work/logs/app.log")), "the application's working dir");
        ConfigException error = expectThrows(ConfigException.class,
                () -> load("wrapper.readiness.log.pattern=(s3cr3t"));
        assertTrue(error.getMessage().contains("wrapper.readiness.log.pattern is not a valid regular expression:"),
                error.getMessage());
        assertFalse(error.getMessage().contains("s3cr3t"), error.getMessage());
    }

    @Test
    public void userInformationInTheHttpUrlIsWarnedAboutWithoutQuotingIt() throws IOException {
        WrapperConfig config = load("wrapper.readiness.http.url=http://admin:s3cr3t@localhost:8080/health");

        assertEquals(config.probes().httpUrl(), Optional.of("http://admin:s3cr3t@localhost:8080/health"));
        assertTrue(messages(config).contains("wrapper.readiness.http.url has user information, which the http probe"
                + " does not send"), messages(config).toString());
        assertTrue(messages(config).stream().noneMatch(m -> m.contains("s3cr3t")), messages(config).toString());
    }

    @Test
    public void probeIntervalsOfZeroAndFailureCountsBelowOneAreErrors() {
        ConfigException error = expectThrows(ConfigException.class, () -> load("wrapper.readiness.interval=0",
                "wrapper.liveness.interval=0", "wrapper.liveness.failures=0", "wrapper.liveness.action=SOMETIMES"));

        List<String> problems = error.problems().stream().map(ConfigException::getMessage).toList();
        assertEquals(problems.size(), 4, problems.toString());
        assertTrue(problems.stream().anyMatch(m -> m.contains("wrapper.readiness.interval must be at least 1 s")),
                problems.toString());
        assertTrue(problems.stream().anyMatch(m -> m.contains("wrapper.liveness.interval must be at least 1 s")),
                problems.toString());
        assertTrue(problems.stream().anyMatch(m -> m.contains("wrapper.liveness.failures must be at least 1")),
                problems.toString());
        assertTrue(problems.stream().anyMatch(m -> m.contains("invalid value 'SOMETIMES' for wrapper.liveness.action")),
                problems.toString());
        expectThrows(ConfigException.class, () -> load("wrapper.liveness.failures=-2"));
    }

    @Test
    public void invalidValuesOfTheSupervisionKeysAreErrorsNowThatTheyTakeEffect() {
        ConfigException error = expectThrows(ConfigException.class,
                () -> load("wrapper.startup.timeout=soon", "wrapper.control=SOMETIMES"));

        assertEquals(error.problems().size(), 2, error.getMessage());
        assertTrue(error.getMessage().contains("wrapper.startup.timeout"), error.getMessage());
        assertTrue(error.getMessage().contains("wrapper.control"), error.getMessage());
    }

    @Test
    public void aValidReadinessSettingHasNoEffectBeforeItsPhase() throws IOException {
        WrapperConfig config = load("wrapper.readiness.interval=7");

        assertEquals(config.probes().readinessInterval(), ConfigKeys.READINESS_INTERVAL.status().takesEffect()
                ? Duration.ofSeconds(7) : Duration.ofSeconds(1));
    }

    @Test
    public void emptyValuesMeanNotSet() throws IOException {
        WrapperConfig config = load("wrapper.restart.delay=", "wrapper.ntservice.account=",
                "wrapper.ntservice.password=", "wrapper.java.additional.1=", "wrapper.java.additional.2=-Dx");

        // Not set means the declared default, as if the line were absent.
        assertEquals(config.lifecycle().restartDelay(), Duration.ofSeconds(5));
        assertEquals(config.service().account(), Optional.empty());
        assertEquals(config.service().password(), Optional.empty());
        assertEquals(config.java().jvmOptions(), List.of("-Dx"));
    }

    @Test
    public void exitCodeZeroFollowsTheDefaultActionUnlessSetItself() throws IOException {
        WrapperConfig restarting = load("wrapper.on_exit.default=RESTART");
        WrapperConfig cleanExitEnds = load("wrapper.on_exit.default=RESTART", "wrapper.on_exit.0=SHUTDOWN");

        assertEquals(restarting.lifecycle().exitAction(0), ExitAction.RESTART);
        assertEquals(cleanExitEnds.lifecycle().exitAction(0), ExitAction.SHUTDOWN);
        assertEquals(cleanExitEnds.lifecycle().exitAction(1), ExitAction.RESTART);
    }

    @Test
    public void logFormatsDefaultWhenAbsentAndStayBlankWhenWrittenBlank() throws IOException {
        LogConfig absent = load().log();
        LogConfig blank = load("wrapper.logfile.format=", "wrapper.console.format=").log();

        assertEquals(absent.logfileFormat().map(LineFormat::toString), Optional.of("LPTM"));
        assertEquals(absent.consoleFormat(), Optional.of(LineFormat.CONSOLE_DEFAULT));
        // YAJSW turns the output off for a blank format.
        assertEquals(blank.logfileFormat(), Optional.empty());
        assertEquals(blank.consoleFormat(), Optional.empty());
    }

    @Test
    public void theLogFileIsWrapperLogBesideTheConfigurationUnlessSetAndOffWhenBlank() throws IOException {
        assertEquals(load().log().logfile(), Optional.of(dir.resolve("wrapper.log")));
        assertEquals(load("wrapper.logfile=logs/w.log").log().logfile(), Optional.of(dir.resolve("logs/w.log")));
        assertEquals(load("wrapper.logfile=").log().logfile(), Optional.empty());
        assertEquals(load("wrapper.logfile=  ").log().logfile(), Optional.empty());
    }

    @Test
    public void theLogFileLevelIsRaisedByDebugAndTheEncodingDefaultsToTheJvms() throws IOException {
        LogConfig plain = load("wrapper.logfile.loglevel=ERROR").log();
        LogConfig debug = load("wrapper.logfile.loglevel=ERROR", "wrapper.debug=true").log();

        assertEquals(plain.logfileThreshold(), LogLevel.ERROR);
        assertEquals(debug.logfileThreshold(), LogLevel.DEBUG);
        assertEquals(plain.encoding(), Charset.defaultCharset());
        assertEquals(load("wrapper.log.encoding=windows-1252").log().encoding(), Charset.forName("windows-1252"));
        assertEquals(load("wrapper.log.encoding=  ").log().encoding(), Charset.defaultCharset());
    }

    @Test
    public void anUnknownLogEncodingANegativeMaxfilesOrAnUnusableFileNameIsAnError() throws IOException {
        ConfigException error = expectThrows(ConfigException.class,
                () -> load("wrapper.log.encoding=klingon-8", "wrapper.logfile.maxfiles=-1",
                        "wrapper.logfile=logs/ROLLNUM"));

        List<String> problems = error.problems().stream().map(Throwable::getMessage).toList();
        assertTrue(problems.stream().anyMatch(p -> p.contains("unknown character encoding 'klingon-8' for"
                + " wrapper.log.encoding")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("wrapper.logfile.maxfiles=-1 is negative")),
                problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("wrapper.logfile: the file name is only ROLLNUM")),
                problems.toString());
    }

    @Test
    public void rollModesTakeYajswsValuesAndTanukisWithDaysKeptOnlyUnderDate() throws IOException {
        assertEquals(load().log().rollMode(), Optional.empty());
        assertEquals(load().log().maxDays(), -1);
        LogConfig dated = load("wrapper.logfile=w_YYYYMMDD.log", "wrapper.logfile.rollmode=DATE",
                "wrapper.logfile.maxdays=3").log();
        assertEquals(dated.rollMode(), Optional.of(RollMode.DATE));
        assertEquals(dated.maxDays(), 3);
        // Tanuki's values, from files derived from its templates (D22)
        assertEquals(load("wrapper.logfile.rollmode=SIZE_OR_JVM").log().rollMode(), Optional.of(RollMode.JVM));
        assertEquals(load("wrapper.logfile.rollmode=SIZE_OR_WRAPPER").log().rollMode(),
                Optional.of(RollMode.WRAPPER));
        assertEquals(load("wrapper.logfile.rollmode=SIZE").log().rollMode(), Optional.of(RollMode.SIZE));
        assertEquals(load("wrapper.logfile.rollmode=NONE").log().rollMode(), Optional.of(RollMode.NONE));

        WrapperConfig undatedDays = load("wrapper.logfile.maxdays=3");
        assertTrue(undatedDays.warnings().stream().map(ConfigWarning::toString).anyMatch(w -> w.endsWith(
                "wrapper.logfile.maxdays has an effect only with wrapper.logfile.rollmode=DATE")),
                undatedDays.warnings().toString());
        assertFalse(load("wrapper.logfile=w_YYYYMMDD.log", "wrapper.logfile.rollmode=DATE",
                "wrapper.logfile.maxdays=3").warnings().stream().anyMatch(w -> w.toString().contains("maxdays")));

        ConfigException undated = expectThrows(ConfigException.class,
                () -> load("wrapper.logfile=logs/wrapper.log", "wrapper.logfile.rollmode=DATE"));
        assertTrue(undated.problems().stream().map(Throwable::getMessage).anyMatch(p -> p.contains(
                "wrapper.logfile.rollmode=DATE needs YYYYMMDD in the name of wrapper.logfile")),
                undated.problems().toString());
        ConfigException unknown = expectThrows(ConfigException.class, () -> load("wrapper.logfile.rollmode=WEEKLY"));
        assertTrue(unknown.problems().toString().contains("invalid value 'WEEKLY' for wrapper.logfile.rollmode"),
                unknown.problems().toString());
    }

    @Test
    public void whatIsWrongWithAFormatIsAWarningAtItsLine() throws IOException {
        WrapperConfig config = load("wrapper.console.format=L?M\\n", "wrapper.logfile.format=none");

        assertEquals(config.log().consoleFormat().map(LineFormat::toString), Optional.of("LM"));
        assertEquals(config.log().consoleFormat().orElseThrow().lineSeparator(), "\n");
        assertEquals(config.log().logfileFormat(), Optional.empty(), "no letter of 'none' is a field");
        List<String> warnings = config.warnings().stream().map(ConfigWarning::toString).toList();
        assertTrue(warnings.stream().anyMatch(w -> w.endsWith(
                "wrapper.conf:1: wrapper.console.format: '?' is not a format letter (L P D T Z M); skipped")),
                warnings.toString());
        assertTrue(warnings.stream().anyMatch(
                w -> w.contains("wrapper.logfile.format: no format letter, so nothing is written there")),
                warnings.toString());
    }

    @Test
    public void debugLevelDefaultsToFullDetail() throws IOException {
        assertEquals(load().log().debugLevel(), DebugDetail.ALL);
        assertEquals(load("wrapper.debug.level=0").log().debugLevel(), DebugDetail.NONE);
        assertEquals(load("wrapper.debug.level=1").log().debugLevel(), DebugDetail.APPLICATION);
        assertEquals(load("wrapper.debug.level=2").log().debugLevel(), DebugDetail.SUPERVISION);
    }

    private static final List<String> NOT_A_DEBUG_LEVEL = List.of("-1", "4", "garbage", "99999999999");

    @Test
    public void aDebugLevelOutsideZeroToThreeIsAnErrorUnderDebug() {
        for (String level : NOT_A_DEBUG_LEVEL) {
            ConfigException error = expectThrows(ConfigException.class,
                    () -> load("wrapper.debug=true", "wrapper.debug.level=" + level));

            List<String> problems = error.problems().stream().map(Throwable::getMessage).toList();
            assertEquals(problems.size(), 1, problems.toString());
            assertTrue(problems.get(0).contains("wrapper.debug.level=" + level
                    + " is not a whole number from 0 to 3"), problems.toString());
        }
    }

    @Test
    public void aDebugLevelOutsideZeroToThreeOnlyWarnsWhileDebugIsOff() throws IOException {
        for (String level : NOT_A_DEBUG_LEVEL) {
            WrapperConfig config = load("wrapper.debug.level=" + level);

            assertEquals(config.log().debugLevel(), DebugDetail.ALL, level);
            assertTrue(config.warnings().stream().map(ConfigWarning::toString).anyMatch(w -> w.contains(
                    "wrapper.debug.level=" + level + " is not a whole number from 0 to 3; it has no effect while"
                            + " wrapper.debug is false")), config.warnings().toString());
        }
    }

    @Test
    public void aBlankDebugLevelIsTheDefault() throws IOException {
        WrapperConfig config = load("wrapper.debug=true", "wrapper.debug.level=");

        assertEquals(config.log().debugLevel(), DebugDetail.ALL);
    }

    @Test
    public void anEncodingThatCanOnlyBeReadIsAnError() {
        if (!Charset.isSupported("ISO-2022-CN")) {
            throw new SkipException("this runtime has no ISO-2022-CN (module jdk.charsets)");
        }
        ConfigException error = expectThrows(ConfigException.class, () -> load("wrapper.log.encoding=ISO-2022-CN"));

        List<String> problems = error.problems().stream().map(Throwable::getMessage).toList();
        assertTrue(problems.stream().anyMatch(p -> p.contains(
                "the character encoding 'ISO-2022-CN' for wrapper.log.encoding can be read but not written")),
                problems.toString());
    }

    @Test
    public void debugRaisesTheConsoleLevelAndAppliesTheDebugLevel() throws IOException {
        LogConfig plain = load("wrapper.debug.level=1").log();
        LogConfig quiet = load("wrapper.console.loglevel=ERROR").log();
        LogConfig debug = load("wrapper.console.loglevel=ERROR", "wrapper.debug=true", "wrapper.debug.level=1").log();

        assertEquals(plain.consoleThreshold(), LogLevel.INFO);
        assertEquals(quiet.consoleThreshold(), LogLevel.ERROR);
        assertEquals(debug.consoleThreshold(), LogLevel.DEBUG);
        // YAJSW reads wrapper.debug.level only under wrapper.debug.
        assertEquals(plain.debugDetail(), DebugDetail.ALL);
        assertEquals(debug.debugDetail(), DebugDetail.APPLICATION);
    }

    @Test
    public void aLibraryPathEntryMayListSeveralPaths() throws IOException {
        String separator = ";";
        // A NUL is invalid in a path on every platform; the file cannot hold one, a system property can.
        WrapperConfig config = loadWith(Map.of("wrapper.java.library.path.1", "a" + separator + "\"b c\"" + separator
                + separator + "d" + separator + "bad\u0000path"), "wrapper.java.library.path.2=e");

        assertEquals(config.java().libraryPath(), List.of(dir.resolve("a"), dir.resolve("b c"), dir.resolve("d"),
                dir.resolve("e")));
        assertTrue(messages(config).stream().anyMatch(m -> m.startsWith("one of the paths in"
                + " wrapper.java.library.path.1 is skipped")), messages(config).toString());
    }

    @Test
    public void uncPathsFromTheFileKeepTheirPrefix() throws IOException {
        // Escaped as YAJSW files write it, and written raw, which YAJSW reads as a single backslash too.
        WrapperConfig escaped = load("wrapper.java.app.jar=\\\\\\\\srv\\\\share\\\\app.jar");
        WrapperConfig raw = load("wrapper.java.app.jar=\\\\srv\\share\\app.jar");

        assertEquals(escaped.java().jar().orElseThrow().toString().replace('/', '\\'),
                dir.resolve("\\\\srv\\share\\app.jar").normalize().toString().replace('/', '\\'));
        assertTrue(raw.java().jar().orElseThrow().toString().replace('/', '\\').endsWith("\\srv\\share\\app.jar"),
                raw.java().jar().toString());
    }

    @Test
    public void aQuotedSingleLibraryPathIsUnquoted() throws IOException {
        WrapperConfig config = load("wrapper.java.library.path.1=\"lib dir\"");

        assertEquals(config.java().libraryPath(), List.of(dir.resolve("lib dir")));
    }

    @Test
    public void aSingleInvalidLibraryPathIsStillAnError() {
        ConfigException e = expectThrows(ConfigException.class,
                () -> loadWith(Map.of("wrapper.java.library.path.1", "bad\u0000path")));

        assertTrue(e.getMessage().contains("invalid path"), e.getMessage());
    }

    @Test
    public void environmentSetBySystemPropertiesComesLastSoItWinsOverOtherSpellings() throws IOException {
        WrapperConfig config = loadWith(Map.of("wrapper.app.env.Path", "system"),
                "wrapper.app.env.Path=file1", "wrapper.app.env.PATH=file2");

        assertEquals(List.copyOf(config.java().environment().entrySet()),
                List.of(Map.entry("PATH", "file2"), Map.entry("Path", "system")));
    }

    @Test
    public void environmentNamesDifferingOnlyInCaseWarn() throws IOException {
        WrapperConfig config = load("wrapper.app.env.Path=a", "wrapper.app.env.PATH=b");

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Path", "a");
        expected.put("PATH", "b");
        assertEquals(config.java().environment(), expected);
        assertTrue(messages(config).contains("wrapper.app.env.PATH and wrapper.app.env.Path differ only in case;"
                + " Windows treats them as one variable"), messages(config).toString());
    }

    @Test
    public void fatalKeysFailWithEveryError() throws IOException {
        Path file = write("wrapper.app.account=svc", "wrapper.app.password=Pa55w0rd");
        LoadedConfig loaded = new ConfigLoader(Map.of(), Map.of(), false, ConfigKeys.registry()).load(file);

        ConfigException e = expectThrows(ConfigException.class, () -> WrapperConfig.from(loaded));

        assertEquals(e.problems().size(), 2);
        assertFalse(e.getMessage().contains("Pa55w0rd"), e.getMessage());
    }

    @Test
    public void warningsComeFromLoadingValidationAndTyping() throws IOException {
        WrapperConfig config = load("wrapper.tmp.path=${missing}", "wrapper.app.env.A=1", "wrapper.app.env.a=2");

        List<String> messages = messages(config);
        assertTrue(messages.get(0).contains("unresolved variable ${missing}"), messages.toString());
        assertTrue(messages.get(messages.size() - 1).contains("differ only in case"), messages.toString());
    }

    @Test
    public void toStringNeverShowsValues() throws IOException {
        WrapperConfig config = load("pw=s3cret", "wrapper.ntservice.password=${pw}",
                "wrapper.app.parameter.1=--password=${pw}", "wrapper.readiness.http.url=http://u:${pw}@localhost/",
                "wrapper.logfile=logs/${pw}.log");

        for (Object view : List.of(config, config.java(), config.service(), config.lifecycle(), config.probes(),
                config.log(), config.service().password().orElseThrow())) {
            assertFalse(view.toString().contains("s3cret"), view.toString());
        }
    }

    @Test
    public void enumsDeclareTheRegistryValues() {
        assertEquals(ConfigKeys.CONTROL.values(), names(ControlMode.values()));
        assertEquals(ConfigKeys.ON_EXIT.values(), names(ExitAction.values()));
        assertEquals(ConfigKeys.NTSERVICE_STARTTYPE.values(), names(StartType.values()));
        assertEquals(ConfigKeys.LOGFILE_ROLLMODE.values(), names(RollMode.values()));
        assertEquals(ConfigKeys.CONSOLE_LOGLEVEL.values(), names(LogLevel.values()));
        assertEquals(ConfigKeys.LIVENESS_ACTION.values(), names(FailureAction.values()));
    }

    @Test
    public void simpleNamesOfMainClasses() {
        assertEquals(ServiceConfig.simpleName("Main"), "Main");
        assertEquals(ServiceConfig.simpleName("a.b.Main"), "Main");
        assertEquals(ServiceConfig.simpleName("a.b.Outer$Inner"), "Inner");
    }

    private static List<String> names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).toList();
    }

    private static List<String> messages(WrapperConfig config) {
        return config.warnings().stream().map(ConfigWarning::message).toList();
    }

    @Test
    public void theStopBudgetAddsTheJvmExitTimeoutToTheShutdownTimeout() throws IOException {
        LifecycleConfig lifecycle = load("wrapper.java.app.mainclass=Main", "wrapper.shutdown.timeout=20",
                "wrapper.jvm_exit.timeout=15", "wrapper.startup.delay=3").lifecycle();

        assertEquals(lifecycle.jvmExitTimeout(), Duration.ofSeconds(15));
        assertEquals(lifecycle.stopBudget(), Duration.ofSeconds(35));
        assertEquals(lifecycle.startupDelay(), Duration.ofSeconds(3));
    }

    private WrapperConfig load(String... lines) throws IOException {
        return loadWith(Map.of(), lines);
    }

    private WrapperConfig loadWith(Map<String, String> systemProperties, String... lines) throws IOException {
        Path file = write(lines);
        ConfigLoader loader = new ConfigLoader(systemProperties, Map.of(), false, ConfigKeys.registry());
        return WrapperConfig.from(loader.load(file));
    }

    private Path write(String... lines) throws IOException {
        return Files.writeString(dir.resolve("wrapper.conf"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }
}
