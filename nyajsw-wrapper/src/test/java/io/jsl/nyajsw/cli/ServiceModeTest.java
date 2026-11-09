package io.jsl.nyajsw.cli;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import io.jsl.nyajsw.agentlink.AgentJar;
import io.jsl.nyajsw.config.ConfigKeys;
import io.jsl.nyajsw.config.ConfigLoader;
import io.jsl.nyajsw.config.WrapperConfig;
import io.jsl.nyajsw.launch.ClasspathExpander;
import io.jsl.nyajsw.launch.JavaCommandBuilder;
import io.jsl.nyajsw.launch.TestApplication;
import io.jsl.nyajsw.lifecycle.State;
import io.jsl.nyajsw.lifecycle.Supervisor;
import io.jsl.nyajsw.log.WrapperLog;
import io.jsl.nyajsw.platform.FakeServiceHost;
import io.jsl.nyajsw.platform.ProcessTools;
import io.jsl.nyajsw.platform.ServiceControl;
import io.jsl.nyajsw.platform.ServiceExit;
import io.jsl.nyajsw.platform.ServiceState;
import io.jsl.nyajsw.platform.unsupported.UnsupportedPlatform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** Service mode under a {@link FakeServiceHost}, with {@link TestApplication} as the application. */
public class ServiceModeTest {

    private static final Duration WAIT = Duration.ofMinutes(1);

    private Path dir;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-service-mode").toRealPath();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Path conf(String... extra) throws Exception {
        String classes = Path.of(TestApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString().replace('\\', '/');
        List<String> lines = new ArrayList<>(List.of(
                "wrapper.java.command=" + Path.of(System.getProperty("java.home"), "bin", "java").toString()
                        .replace('\\', '/'),
                "wrapper.java.app.mainclass=" + TestApplication.class.getName(),
                "wrapper.java.classpath.1=" + classes,
                "wrapper.ntservice.name=svc",
                "wrapper.shutdown.timeout=1"));
        lines.addAll(List.of(extra));
        return Files.writeString(dir.resolve("wrapper.conf"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static WrapperConfig load(Path conf) {
        return WrapperConfig.from(new ConfigLoader(Map.of(), Map.of(), false, ConfigKeys.registry()).load(conf));
    }

    private ServiceMode mode(Path conf) {
        return new ServiceMode(conf.toString(), JavaCommandBuilder.forThisJvm(), ClasspathExpander.forThisHost(),
                name -> dir.resolve("bootstrap-" + name + ".log"), () -> ProcessTools.NONE);
    }

    private String log() throws IOException {
        Path file = dir.resolve("wrapper.log");
        return Files.exists(file) ? Files.readString(file, Charset.defaultCharset()) : "";
    }

    /** Runs {@code mode} under {@code host} on a thread of its own, as the service thread. */
    private static CompletableFuture<Void> serve(FakeServiceHost host, ServiceMode mode) {
        return CompletableFuture.runAsync(() -> host.run(mode));
    }

    private void awaitLog(String text) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!log().contains(text)) {
            assertTrue(System.nanoTime() < deadline, "no '" + text + "' in the log: " + log());
            Thread.sleep(50); // the application runs in a real JVM; there is no event to wait on
        }
    }

    @Test(timeOut = 120_000)
    public void theServiceRunsUntilTheServiceManagerStopsIt() throws Exception {
        if (AgentJar.locate().isEmpty()) {
            throw new SkipException("the agent's jar is not built; run the whole build (mvn verify)");
        }
        FakeServiceHost host = new FakeServiceHost("svc");
        ServiceMode mode = mode(conf("wrapper.app.parameter.1=options"));
        CompletableFuture<Void> service = serve(host, mode);
        try {
            assertTrue(host.awaitRunning(WAIT));
            assertTrue(host.states().contains(ServiceState.RUNNING), "it ended without running: " + host.states() + log());
            awaitLog("options=");
            awaitLog("the agent of jvm 1 has connected"); // it connects on a thread of its own

            assertTrue(host.stop(ServiceControl.STOP));
        } finally {
            host.stop(ServiceControl.STOP); // no parked JVM is left behind when an assertion fails
            service.get(1, TimeUnit.MINUTES);
        }

        assertEquals(host.exit(), Optional.of(ServiceExit.SUCCESS), log());
        List<ServiceState> states = host.states();
        assertEquals(states.get(0), ServiceState.START_PENDING);
        assertTrue(states.contains(ServiceState.RUNNING), states.toString());
        assertEquals(states.subList(states.indexOf(ServiceState.RUNNING), states.size()).stream().distinct().toList(),
                List.of(ServiceState.RUNNING, ServiceState.STOP_PENDING, ServiceState.STOPPED), states.toString());
        assertEquals(mode.exitCode(), 0);
        String log = log();
        assertTrue(log.contains("running as the service 'svc'"), log);
        assertTrue(log.contains("the Service Control Manager asks the service to stop (STOP)"), log);
        assertTrue(log.lines().anyMatch(line -> line.contains("options=[") && line.contains("-Xrs")), log);
        assertTrue(log.contains("supervision ended STOPPED; the service has stopped"), log);
        // The agent is in by default (awaited before the stop): it ends the application with 0.
        assertTrue(log.contains("the agent of jvm 1 has connected (protocol 1)"), log);
        assertTrue(log.contains("the agent of jvm 1 is ending the application"), log);
        assertFalse(log.contains("warning: no graceful stop strategy"), log);
        Path bootstrap = dir.resolve("bootstrap-svc.log");
        assertTrue(!Files.exists(bootstrap) || Files.readString(bootstrap, StandardCharsets.UTF_8).isBlank(),
                "nothing went wrong");
    }

    @Test(timeOut = 120_000)
    public void withoutReducedSignalsTheApplicationJvmHasNoXrs() throws Exception {
        FakeServiceHost host = new FakeServiceHost("svc");
        CompletableFuture<Void> service = serve(host, mode(conf("wrapper.app.parameter.1=options",
                "wrapper.ntservice.reduce_signals=false")));
        try {
            assertTrue(host.awaitRunning(WAIT));
            awaitLog("options=");
        } finally {
            host.stop(ServiceControl.STOP);
            service.get(1, TimeUnit.MINUTES);
        }

        assertTrue(log().lines().anyMatch(line -> line.contains("options=[") && !line.contains("-Xrs")), log());
    }

    @Test
    public void theWrapperAddsXrsOnlyWhileReduceSignalsIsSet() throws Exception {
        assertEquals(ServiceMode.wrapperOptions(load(conf())), List.of("-Xrs"));
        assertEquals(ServiceMode.wrapperOptions(load(conf("wrapper.ntservice.reduce_signals=false"))), List.of());
    }

    @Test(timeOut = 120_000)
    public void aConfigurationThatCannotBeReadFailsTheServiceAndGoesToTheBootstrapLog() throws Exception {
        FakeServiceHost host = new FakeServiceHost("svc");
        ServiceMode mode = mode(conf("wrapper.java.maxmemory=lots"));

        host.run(mode);

        assertEquals(host.exit(), Optional.of(new ServiceExit(ExitCode.CONFIGURATION_ERROR.code())));
        assertEquals(host.reports(), List.of(new FakeServiceHost.Report(ServiceState.START_PENDING, Duration.ZERO),
                new FakeServiceHost.Report(ServiceState.START_PENDING, ServiceMode.READING_WAIT_HINT),
                new FakeServiceHost.Report(ServiceState.STOPPED, Duration.ZERO)));
        String bootstrap = Files.readString(dir.resolve("bootstrap-svc.log"), StandardCharsets.UTF_8);
        assertTrue(bootstrap.contains("wrapper.java.maxmemory"), bootstrap);
        assertTrue(bootstrap.contains("the service 'svc' cannot start: its configuration"), bootstrap);
        assertEquals(mode.exitCode(), ExitCode.CONFIGURATION_ERROR.code());
    }

    @Test(timeOut = 120_000)
    public void anUnknownProbeTypeFailsTheServiceWithTheConfigurationErrorCode() throws Exception {
        FakeServiceHost host = new FakeServiceHost("svc");

        host.run(mode(conf("wrapper.readiness.type=telepathy")));

        assertEquals(host.exit(), Optional.of(new ServiceExit(ExitCode.CONFIGURATION_ERROR.code())), log());
        assertTrue(log().contains("wrapper.readiness.type: unknown probe type telepathy"), log());
    }

    @Test(timeOut = 120_000)
    public void anApplicationThatCannotBeKeptRunningFailsTheServiceWithItsCode() throws Exception {
        FakeServiceHost host = new FakeServiceHost("svc");

        host.run(mode(conf("wrapper.app.parameter.1=report", "wrapper.app.parameter.2=5",
                "wrapper.on_exit.default=RESTART", "wrapper.restart.delay=0", "wrapper.max_failed_invocations=1")));

        assertEquals(host.exit(), Optional.of(new ServiceExit(5)), log());
        assertTrue(log().contains("supervision ended FAILED; the service reports the error code 5"), log());
    }

    @Test(timeOut = 120_000)
    public void anApplicationThatEndsByItselfWithAnErrorFailsTheService() throws Exception {
        FakeServiceHost host = new FakeServiceHost("svc");

        host.run(mode(conf("wrapper.app.parameter.1=report", "wrapper.app.parameter.2=3")));

        assertEquals(host.exit(), Optional.of(new ServiceExit(3)), log());
        assertTrue(log().contains("supervision ended STOPPED; the service reports the error code 3"), log());
    }

    @Test(timeOut = 120_000)
    public void anApplicationThatEndsByItselfWithZeroIsAServiceThatStopped() throws Exception {
        FakeServiceHost host = new FakeServiceHost("svc");

        host.run(mode(conf("wrapper.app.parameter.1=report", "wrapper.app.parameter.2=0")));

        assertEquals(host.exit(), Optional.of(ServiceExit.SUCCESS), log());
    }

    @Test(timeOut = 120_000)
    public void aStopBeforeSupervisionBeginsStartsNothing() throws Exception {
        FakeServiceHost host = new FakeServiceHost("svc");
        ServiceMode mode = mode(conf("wrapper.app.parameter.1=park"));

        mode.stop(ServiceControl.STOP);
        host.run(mode);

        assertEquals(host.exit(), Optional.of(ServiceExit.SUCCESS));
        assertFalse(log().contains("launching"), log());
    }

    @Test
    public void supervisionThatFailsIsAFailedServiceAndOneThatStoppedIsNot() {
        assertEquals(ServiceMode.exit(new Supervisor.Outcome(State.STOPPED, OptionalInt.of(143)), true),
                ServiceExit.SUCCESS, "an application terminated by the stop is not a failure");
        assertEquals(ServiceMode.exit(new Supervisor.Outcome(State.STOPPED, OptionalInt.of(143)), false),
                new ServiceExit(143), "nobody asked it to end");
        assertEquals(ServiceMode.exit(new Supervisor.Outcome(State.STOPPED, OptionalInt.empty()), false),
                ServiceExit.SUCCESS, "left running as wrapper.control allows");
        assertEquals(ServiceMode.exit(new Supervisor.Outcome(State.FAILED, OptionalInt.of(5)), true), new ServiceExit(5));
        assertEquals(ServiceMode.exit(new Supervisor.Outcome(State.FAILED, OptionalInt.empty()), true),
                new ServiceExit(ExitCode.UNEXPECTED_ERROR.code()));
    }

    @Test
    public void theBootstrapLogIsBesideTheConfiguration() throws Exception {
        Path conf = conf();

        assertEquals(Main.bootstrapFiles(conf.toString()).apply("svc"), dir.resolve("nyajsw-bootstrap-svc.log"));
        assertEquals(Main.bootstrapFiles("wrapper.conf").apply("svc"),
                Path.of("").toAbsolutePath().resolve("nyajsw-bootstrap-svc.log"), "a relative one");
        assertEquals(Main.bootstrapFiles("bad\u0000path").apply("svc"), WrapperLog.bootstrapFile("svc"),
                "a path that cannot be parsed falls back to the temporary directory");
    }

    @Test
    public void theCommandLineRunsServiceMode() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        int code = Main.run(new String[] {"--service", conf().toString()}, new PrintStream(bytes, true,
                StandardCharsets.UTF_8), () -> new UnsupportedPlatform("Linux"));

        assertEquals(code, ExitCode.UNSUPPORTED_PLATFORM.code(), bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void aHostWithoutServicesIsToldOnTheConsoleAndInTheBootstrapLogWithExitCodeFour() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        int code = Main.serviceMode(mode(conf()), new PrintStream(bytes, true, StandardCharsets.UTF_8),
                () -> new UnsupportedPlatform("Linux"));

        assertEquals(code, ExitCode.UNSUPPORTED_PLATFORM.code());
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("cannot run as a service: nyajsw runs services on"
                + " Windows only"), bytes.toString(StandardCharsets.UTF_8));
        // Windows never named the service, so the file is named after the configuration.
        assertFalse(Files.exists(dir.resolve("bootstrap-svc.log")));
        assertTrue(Files.readString(dir.resolve("bootstrap-wrapper.log"), StandardCharsets.UTF_8)
                .contains("cannot run as a service: nyajsw runs services on Windows only"));
    }

    @Test
    public void theBootstrapLogOfAnUnnamedServiceIsNamedAfterTheConfiguration() {
        assertEquals(ServiceMode.fileStem("C:\\conf\\service.conf"), "service");
        assertEquals(ServiceMode.fileStem("conf/app/app.service.conf"), "app.service");
        assertEquals(ServiceMode.fileStem("wrapper"), "wrapper");
        assertEquals(ServiceMode.fileStem(".conf"), ".conf");
        assertEquals(ServiceMode.fileStem("conf/"), "service");
    }

    @Test
    public void aBootstrapLogThatCannotBeWrittenBesideTheConfigurationGoesToTheFallback() throws Exception {
        try (BootstrapLog bootstrap = new BootstrapLog(dir.resolve("missing/bootstrap.log"), dir.resolve("fallback.log"))) {
            bootstrap.accept("the log file cannot be opened");
        }

        assertFalse(Files.exists(dir.resolve("missing")));
        assertTrue(Files.readString(dir.resolve("fallback.log"), StandardCharsets.UTF_8)
                .contains("the log file cannot be opened"));
    }

    @Test(timeOut = 120_000)
    public void aLogFileThatMayNotBeWrittenIsReportedInTheBootstrapLogWithTheAccount() throws Exception {
        Path logfile = Files.writeString(dir.resolve("locked.log"), "");
        if (!logfile.toFile().setWritable(false) || Files.isWritable(logfile)) {
            throw new SkipException("this account may write a read-only file");
        }
        try {
            FakeServiceHost host = new FakeServiceHost("svc");

            host.run(mode(conf("wrapper.logfile=locked.log", "wrapper.app.parameter.1=report",
                    "wrapper.app.parameter.2=0")));

            assertEquals(host.exit(), Optional.of(ServiceExit.SUCCESS));
            String bootstrap = Files.readString(dir.resolve("bootstrap-svc.log"), StandardCharsets.UTF_8);
            assertTrue(bootstrap.contains("cannot open the log file"), bootstrap);
            assertTrue(bootstrap.contains("the wrapper runs as '" + System.getProperty("user.name") + "'"), bootstrap);
            assertTrue(bootstrap.contains("or change wrapper.logfile; the log goes on without it"), bootstrap);
        } finally {
            logfile.toFile().setWritable(true);
        }
    }
}
