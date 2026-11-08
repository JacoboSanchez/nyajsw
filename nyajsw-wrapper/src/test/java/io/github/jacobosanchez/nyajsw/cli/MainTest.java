package io.github.jacobosanchez.nyajsw.cli;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.agentlink.AgentJar;
import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ConfigLoader;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.launch.ClasspathExpander;
import io.github.jacobosanchez.nyajsw.launch.JavaCommandBuilder;
import io.github.jacobosanchez.nyajsw.launch.TestApplication;
import io.github.jacobosanchez.nyajsw.lifecycle.State;
import io.github.jacobosanchez.nyajsw.lifecycle.Supervisor;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;
import io.github.jacobosanchez.nyajsw.platform.ProcessTools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** Console mode end to end, with {@link TestApplication} as the application. */
public class MainTest {

    private Path dir;
    private ByteArrayOutputStream bytes;
    private PrintStream out;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-cli");
        bytes = new ByteArrayOutputStream();
        out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private String output() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private Path conf(String... extra) throws Exception {
        String classes = Path.of(TestApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString().replace('\\', '/');
        List<String> lines = new ArrayList<>(List.of(
                "wrapper.java.command=" + Path.of(System.getProperty("java.home"), "bin", "java").toString()
                        .replace('\\', '/'),
                "wrapper.java.app.mainclass=" + TestApplication.class.getName(),
                "wrapper.java.classpath.1=" + classes));
        lines.addAll(List.of(extra));
        return Files.writeString(dir.resolve("wrapper.conf"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    @Test(timeOut = 120_000)
    public void consoleModeRunsTheApplicationAndReturnsItsExitCode() throws Exception {
        Path conf = conf("wrapper.app.parameter.1=report", "wrapper.app.parameter.2=7", "wrapper.tray=true");

        int code = Main.run(new String[] {"-c", conf.toString()}, out);

        assertEquals(code, 7, output());
        assertTrue(output().contains("jvm 1    | to stderr"), output());
        assertTrue(output().contains("jvm 1    | stdin-ended=true"), output()); // standard output too
        assertTrue(output().contains("wrapper  | jvm 1 exited with code 7"), output());
        assertTrue(output().contains("wrapper  | warning: "), output()); // the unsupported wrapper.tray
        // and the default log file beside the configuration, in YAJSW's default file format
        String file = Files.readString(dir.resolve("wrapper.log"), Charset.defaultCharset());
        assertTrue(file.lines().anyMatch(line -> line.startsWith("STATUS | wrapper  | ")
                && line.endsWith(" | jvm 1 exited with code 7")), file);
        assertTrue(file.lines().anyMatch(line -> line.startsWith("INFO   | jvm 1    | ")
                && line.endsWith(" | to stderr")), file);
        assertTrue(file.lines().anyMatch(line -> line.startsWith("INFO   | jvm 1    | ")
                && line.endsWith(" | stdin-ended=true")), file);
    }

    @Test
    public void configurationErrorsAreReportedWithTheirExitCode() throws Exception {
        Path conf = conf("wrapper.java.maxmemory=lots");

        int code = Main.run(new String[] {"--console", conf.toString()}, out);

        assertEquals(code, ExitCode.CONFIGURATION_ERROR.code());
        assertTrue(output().contains("wrapper.java.maxmemory"), output());
    }

    @Test
    public void anUnknownProbeTypeIsAConfigurationErrorBeforeAnythingStarts() throws Exception {
        Path conf = conf("wrapper.liveness.type=process, telepathy");

        int code = Main.run(new String[] {"-c", conf.toString()}, out);

        assertEquals(code, ExitCode.CONFIGURATION_ERROR.code(), output());
        assertTrue(output().contains("wrapper.liveness.type: unknown probe type telepathy;"
                + " known: [process, tcp, http, file, command, log, agent]"), output());
        assertFalse(output().contains("launching jvm 1"), output());
    }

    @Test
    public void launchErrorsEndSupervisionWithoutAStackTrace() throws Exception {
        Path conf = conf("wrapper.java.command=" + dir.resolve("missing/java").toString().replace('\\', '/'),
                "wrapper.max_failed_invocations=1");

        int code = Main.run(new String[] {"-c", conf.toString()}, out);

        assertEquals(code, ExitCode.UNEXPECTED_ERROR.code());
        assertTrue(output().contains("wrapper  | cannot start jvm 1: the Java executable"), output());
        assertTrue(output().contains("supervision ended FAILED; exit code 1 is the wrapper's"), output());
        assertFalse(output().contains("\tat "), output());
    }

    @Test(timeOut = 120_000)
    public void consoleModeRestartsAsTheExitActionsSayAndGivesUpAtTheLimit() throws Exception {
        Path conf = conf("wrapper.app.parameter.1=report", "wrapper.app.parameter.2=5",
                "wrapper.on_exit.default=RESTART", "wrapper.restart.delay=0", "wrapper.max_failed_invocations=2",
                "wrapper.pidfile=run/wrapper.pid", "wrapper.java.pidfile=run/java.pid", "wrapper.logfile.rollmode=JVM");

        int code = Main.run(new String[] {"-c", conf.toString()}, out);

        assertEquals(code, 5, output());
        // the JVM roll mode starts a new file for the restarted application
        Charset charset = Charset.defaultCharset();
        assertTrue(Files.readString(dir.resolve("wrapper.log.1"), charset).contains("| jvm 1 exited with code 5"));
        assertTrue(Files.readString(dir.resolve("wrapper.log"), charset).contains("| jvm 2 exited with code 5"));
        assertTrue(output().contains("launching jvm 2"), output());
        assertTrue(output().contains("jvm 2    | to stderr"), output()); // the restarted run's output is jvm 2
        assertTrue(Files.readString(dir.resolve("wrapper.log"), charset).contains("| jvm 2    | "));
        assertFalse(output().contains("launching jvm 3"), output());
        assertTrue(output().contains("supervision ended FAILED; exit code 5 is the application's"), output());
        assertFalse(Files.exists(dir.resolve("run/wrapper.pid")), "removed at the end");
        assertFalse(Files.exists(dir.resolve("run/java.pid")), "removed when the last invocation ended");
        assertFalse(output().contains("pid file"), output());
    }

    @Test
    public void helpVersionUsageAndCommandsOfLaterPhases() {
        ByteArrayOutputStream versionBytes = new ByteArrayOutputStream();
        assertEquals(Main.run(new String[] {"--version"}, new PrintStream(versionBytes, true, StandardCharsets.UTF_8)),
                ExitCode.SUCCESS.code());
        assertEquals(versionBytes.toString(StandardCharsets.UTF_8).trim(), "nyajsw (development build)");
        assertEquals(Main.run(new String[] {"--help"}, out), ExitCode.SUCCESS.code());
        assertEquals(Main.run(new String[] {}, out), ExitCode.UNEXPECTED_ERROR.code());
        assertEquals(Main.run(new String[] {"-c"}, out), ExitCode.UNEXPECTED_ERROR.code());
        assertEquals(Main.run(new String[] {"-x", "a"}, out), ExitCode.UNEXPECTED_ERROR.code());
        assertEquals(Main.run(new String[] {"--service"}, out), ExitCode.UNEXPECTED_ERROR.code());
        assertEquals(Main.run(new String[] {"-q"}, out), ExitCode.UNEXPECTED_ERROR.code());

        assertTrue(output().contains("usage: nyajsw -c <wrapper.conf>"), output());
        assertTrue(output().contains("nyajsw --service <wrapper.conf>  run as the service"), output());
        assertTrue(output().contains("nyajsw -i <wrapper.conf>    install the Windows service"), output());
    }

    @Test(timeOut = 120_000)
    public void theConsoleUsesTheConfiguredFormat() throws Exception {
        Path conf = conf("wrapper.app.parameter.1=report", "wrapper.app.parameter.2=0", "wrapper.console.format=LM");

        assertEquals(Main.run(new String[] {"-c", conf.toString()}, out), 0, output());

        assertTrue(output().contains("STATUS | supervision ended STOPPED"), output());
        assertFalse(output().contains("wrapper  |"), output());
    }

    @Test(timeOut = 120_000)
    public void debugWritesTheDebugMessagesUpToTheDebugLevel() throws Exception {
        ByteArrayOutputStream supervision = new ByteArrayOutputStream();
        List<String> settings = List.of("wrapper.app.parameter.1=report", "wrapper.app.parameter.2=0",
                "wrapper.console.loglevel=ERROR", "wrapper.debug=true", "wrapper.pidfile=run/wrapper.pid");

        Path conf = conf(withLine(settings, "wrapper.debug.level=1"));
        assertEquals(Main.run(new String[] {"-c", conf.toString()}, out), 0, output());
        conf = conf(withLine(settings, "wrapper.debug.level=2"));
        assertEquals(Main.run(new String[] {"-c", conf.toString()},
                new PrintStream(supervision, true, StandardCharsets.UTF_8)), 0);

        assertTrue(output().contains("wrapper  | state IDLE -> STARTING"), output()); // APPLICATION detail
        assertFalse(output().contains("wrote "), output()); // the pid file's message is SUPERVISION detail
        assertTrue(supervision.toString(StandardCharsets.UTF_8).contains("wrapper  | wrote "),
                supervision.toString(StandardCharsets.UTF_8));
    }

    @Test(timeOut = 120_000)
    public void stopAsksTheAgentSoTheApplicationEndsGracefullyWithZero() throws Exception {
        WrapperConfig config = load(conf("wrapper.app.parameter.1=park", "wrapper.shutdown.timeout=30"));
        if (AgentJar.locate().isEmpty()) {
            throw new SkipException("the agent's jar is not built; run the whole build (mvn verify)");
        }
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            ConsoleMode mode = new ConsoleMode(config, log, JavaCommandBuilder.forThisJvm(),
                    ClasspathExpander.forThisHost(), ProcessTools.NONE);
            CompletableFuture<Integer> run = CompletableFuture.supplyAsync(mode::run);
            try {
                awaitOutput("the agent of jvm 1 has connected");
                long start = System.nanoTime();

                int code = mode.stop();

                assertEquals(code, 0, output());
                assertEquals(run.get(1, TimeUnit.MINUTES), 0);
                assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(25), "well within the 30 s budget");
            } finally {
                mode.stop();
            }
        }
        assertTrue(output().contains("stopping jvm 1: agent, waiting up to "), output());
        assertTrue(output().contains("the agent of jvm 1 is ending the application"), output());
        assertFalse(output().contains("terminating it"), output());
    }

    @Test(timeOut = 120_000)
    public void stopWaitsForTheShutdownTimeoutThenTerminatesTheApplication() throws Exception {
        WrapperConfig config = load(conf("wrapper.app.parameter.1=park", "wrapper.shutdown.timeout=1",
                "wrapper.agent.enabled=false")); // the terminate path
        ConsoleMode mode;
        CompletableFuture<Integer> run;
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            mode = new ConsoleMode(config, log, JavaCommandBuilder.forThisJvm(), ClasspathExpander.forThisHost(), ProcessTools.NONE);
            run = CompletableFuture.supplyAsync(mode::run);
            try {
                awaitOutput("jvm 1    | parked");

                int stopped = mode.stop();

                int code = run.get(1, TimeUnit.MINUTES);
                assertTrue(code != 0, "a killed process does not exit with 0: " + code);
                assertEquals(stopped, code, "stop() returns what run() returns, for the shutdown hook");
            } finally {
                mode.stop(); // no parked JVM is left behind when an assertion fails
            }
        }
        assertTrue(output().contains("did not end within 1 s; terminating it"), output());
        // run() waited for stop(), so its last line was written before the log was closed.
        assertTrue(output().contains("jvm 1 terminated with"), output());
    }

    @Test(timeOut = 120_000)
    public void stopLetsAnApplicationThatEndsByItselfReturnItsCode() throws Exception {
        WrapperConfig config = load(conf("wrapper.agent.enabled=false", "wrapper.app.parameter.1=linger",
                "wrapper.app.parameter.2=500",
                "wrapper.app.parameter.3=3", "wrapper.shutdown.timeout=30"));
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            ConsoleMode mode = new ConsoleMode(config, log, JavaCommandBuilder.forThisJvm(),
                    ClasspathExpander.forThisHost(), ProcessTools.NONE);
            CompletableFuture<Integer> run = CompletableFuture.supplyAsync(mode::run);
            try {
                awaitOutput("jvm 1    | lingering");

                mode.stop();

                assertEquals(run.get(1, TimeUnit.MINUTES), 3);
            } finally {
                mode.stop();
            }
        }
        assertTrue(output().contains("stopping: waiting up to 30 s for jvm 1 to end"), output());
        assertFalse(output().contains("terminating it"), output());
    }

    @Test(timeOut = 120_000)
    public void theStopHookEndsTheJvmWithTheApplicationsExitCode() throws Exception {
        WrapperConfig config = load(conf("wrapper.agent.enabled=false", "wrapper.app.parameter.1=linger",
                "wrapper.app.parameter.2=500",
                "wrapper.app.parameter.3=3", "wrapper.shutdown.timeout=30"));
        List<Integer> halted = new ArrayList<>();
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            ConsoleMode mode = new ConsoleMode(config, log, JavaCommandBuilder.forThisJvm(),
                    ClasspathExpander.forThisHost(), ProcessTools.NONE);
            CompletableFuture<Integer> run = CompletableFuture.supplyAsync(mode::run);
            try {
                awaitOutput("jvm 1    | lingering");

                Main.consoleStopHook(mode, out, halted::add).run();

                assertEquals(halted, List.of(3), "halted once, with the application's code");
                assertEquals(run.get(1, TimeUnit.MINUTES), 3);
            } finally {
                mode.stop();
            }
        }
    }

    @Test
    public void aFailedSupervisionNeverExitsWithZero() {
        assertEquals(Supervision.exitCode(new Supervisor.Outcome(State.STOPPED, OptionalInt.of(7))), 7);
        assertEquals(Supervision.exitCode(new Supervisor.Outcome(State.STOPPED, OptionalInt.of(0))), 0);
        assertEquals(Supervision.exitCode(new Supervisor.Outcome(State.STOPPED, OptionalInt.empty())), 0);
        assertEquals(Supervision.exitCode(new Supervisor.Outcome(State.FAILED, OptionalInt.of(5))), 5);
        assertEquals(Supervision.exitCode(new Supervisor.Outcome(State.FAILED, OptionalInt.of(0))),
                ExitCode.UNEXPECTED_ERROR.code());
        assertEquals(Supervision.exitCode(new Supervisor.Outcome(State.FAILED, OptionalInt.empty())),
                ExitCode.UNEXPECTED_ERROR.code());
    }

    @Test
    public void consoleModeRunsOnce() throws Exception {
        WrapperConfig config = load(conf("wrapper.app.parameter.1=park"));
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            ConsoleMode mode = new ConsoleMode(config, log, JavaCommandBuilder.forThisJvm(),
                    ClasspathExpander.forThisHost(), ProcessTools.NONE);
            mode.stop();
            mode.run();

            expectThrows(IllegalStateException.class, mode::run);
        }
    }

    @Test
    public void stopBeforeTheStartStartsNothing() throws Exception {
        WrapperConfig config = load(conf("wrapper.app.parameter.1=park"));
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            ConsoleMode mode = new ConsoleMode(config, log, JavaCommandBuilder.forThisJvm(),
                    ClasspathExpander.forThisHost(), ProcessTools.NONE);
            mode.stop();

            assertEquals(mode.run(), 0);
        }
        assertFalse(output().contains("launching"), output());
    }

    private static String[] withLine(List<String> lines, String line) {
        List<String> all = new ArrayList<>(lines);
        all.add(line);
        return all.toArray(String[]::new);
    }

    private static WrapperConfig load(Path conf) {
        return WrapperConfig.from(new ConfigLoader(Map.of(), Map.of(), false, ConfigKeys.registry()).load(conf));
    }

    private void awaitOutput(String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
        while (!output().contains(expected)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("never saw '" + expected + "': " + output());
            }
            Thread.sleep(10); // bounded polling of real process output
        }
    }
}
