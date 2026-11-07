package io.github.jacobosanchez.nyajsw.config;

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

        // Planned until P7: what applies is the default, whatever the file says.
        assertEquals(config.probes().readinessTypes(),
                ConfigKeys.READINESS_TYPE.status().takesEffect() ? List.of("tcp", "http") : List.of("process"));
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
        // The agent is enabled by default but not implemented yet (P7), so the defaults do without it.
        assertEquals(lifecycle.stopStrategies(), List.of("terminate"));
        ProbeConfig probes = config.probes();
        assertTrue(probes.agentEnabled());
        assertEquals(probes.readinessTypes(), List.of("process"));
        assertEquals(probes.readinessInterval(), Duration.ofSeconds(1));
        assertEquals(probes.tcpHost(), "127.0.0.1");
        assertEquals(probes.httpStatus(), "200-399");
        assertEquals(probes.livenessTypes(), List.of());
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
        assertEquals(config.lifecycle().stopCommand(), Optional.empty());
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
        WrapperConfig config = loadWith(Map.of("wrapper.tmp.path", "/x/${wrapper.ntservice.password}\0y"),
                "wrapper.ntservice.password=Pa55w0rd");

        assertEquals(config.java().tmpPath(), Optional.empty());
        String message = messages(config).stream().filter(m -> m.startsWith("invalid path")).findFirst().orElseThrow();
        assertFalse(message.contains("Pa55w0rd"), message);
        assertTrue(message.contains("/x/****\\u0000y"), message);
    }

    @Test
    public void derivedDefaultsFollowTheAgentOnceItIsInEffect() throws IOException {
        WrapperConfig config = load("wrapper.agent.enabled=false");

        assertFalse(config.probes().agentEnabled());
        assertEquals(config.lifecycle().stopStrategies(), List.of("terminate"));
        assertEquals(config.probes().livenessTypes(), List.of());
        assertEquals(config.service().name(), Optional.empty());
        assertFalse(ProbeConfig.agentInEffect(false));
        assertEquals(ProbeConfig.agentInEffect(true), ConfigKeys.AGENT_ENABLED.status() == KeyStatus.SUPPORTED);
        assertEquals(LifecycleConfig.defaultStopStrategies(true), List.of("agent", "terminate"));
        assertEquals(ProbeConfig.defaultLivenessTypes(true), List.of("agent"));
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
        WrapperConfig config = load("wrapper.readiness.interval=soon", "wrapper.liveness.action=SOMETIMES");

        assertEquals(config.probes().readinessInterval(), Duration.ofSeconds(1));
        assertEquals(config.probes().livenessAction(), FailureAction.RESTART);
        assertTrue(messages(config).stream().anyMatch(m -> m.startsWith("invalid value 'soon'")),
                messages(config).toString());
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

    @Test
    public void aDebugLevelOutsideZeroToThreeIsAnErrorUnderDebug() {
        for (String level : List.of("-1", "4")) {
            ConfigException error = expectThrows(ConfigException.class,
                    () -> load("wrapper.debug=true", "wrapper.debug.level=" + level));

            List<String> problems = error.problems().stream().map(Throwable::getMessage).toList();
            assertTrue(problems.stream().anyMatch(p -> p.contains("wrapper.debug.level=" + level
                    + " is not between 0 and 3")), problems.toString());
        }
    }

    @Test
    public void aDebugLevelOutsideZeroToThreeOnlyWarnsWhileDebugIsOff() throws IOException {
        WrapperConfig config = load("wrapper.debug.level=4");

        assertEquals(config.log().debugLevel(), DebugDetail.ALL);
        assertTrue(config.warnings().stream().map(ConfigWarning::toString).anyMatch(w -> w.contains(
                "wrapper.debug.level=4 is not between 0 and 3; it has no effect while wrapper.debug is false")),
                config.warnings().toString());
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
