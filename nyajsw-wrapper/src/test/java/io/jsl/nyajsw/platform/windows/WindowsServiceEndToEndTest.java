package io.jsl.nyajsw.platform.windows;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.jsl.nyajsw.agentlink.AgentJar;
import io.jsl.nyajsw.config.ConfigKeys;
import io.jsl.nyajsw.config.ConfigLoader;
import io.jsl.nyajsw.config.WrapperConfig;
import io.jsl.nyajsw.launch.TestApplication;
import io.jsl.nyajsw.log.WrapperLog;
import io.jsl.nyajsw.platform.ServiceException;
import io.jsl.nyajsw.platform.ServiceState;
import io.jsl.nyajsw.platform.ServiceStatus;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The wrapper run as a real service by the Service Control Manager (plan P6.5): install a throwaway
 * service whose binary path runs {@code --service} on this build's classes, start it until it is
 * {@code RUNNING} with {@link TestApplication} as the application, stop it until it is {@code
 * STOPPED}, and remove it; with an HTTP readiness probe and a stop through the agent too (plan P7).
 * Excluded by default; run with {@code mvn -q verify -Pwindows-integration} in an elevated shell.
 *
 * <p>Each test gets a {@link ThrowawayService}, removed after it also when it fails; a failed test
 * prints the wrapper log and the bootstrap logs into the test report.
 */
@Test(groups = "windows-integration")
public class WindowsServiceEndToEndTest {

    private static final Duration WAIT = ThrowawayService.WAIT;

    private ThrowawayService service;
    private Path dir;
    private String name;
    private WindowsServiceController controller;

    @BeforeMethod(alwaysRun = true)
    public void setUp() throws IOException {
        service = null; // one instance runs every method: nothing may be left from the one before
        service = ThrowawayService.create();
        dir = service.dir;
        name = service.name;
        controller = service.controller;
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown(ITestResult result) throws InterruptedException {
        if (service != null) {
            service.close(!result.isSuccess());
        }
    }

    private WrapperConfig config(String... application) throws IOException {
        String classes = ThrowawayService.locationOf(TestApplication.class).toString().replace('\\', '/');
        List<String> lines = new ArrayList<>(List.of(
                "wrapper.ntservice.name=" + name,
                "wrapper.ntservice.description=nyajsw end-to-end test; safe to delete",
                "wrapper.java.command=" + Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
                        .replace('\\', '/'),
                "wrapper.java.app.mainclass=" + TestApplication.class.getName(),
                "wrapper.java.classpath.1=" + classes,
                "wrapper.shutdown.timeout=2",
                "wrapper.tmp.path=jna",
                // The service's temporary files, the fallback bootstrap log among them, stay in the test's directory.
                "wrapper.ntservice.additional.1=-Djava.io.tmpdir=" + dir.resolve("tmp").toString().replace('\\', '/')));
        lines.addAll(List.of(application));
        Path conf = Files.write(dir.resolve("wrapper.conf"), lines, StandardCharsets.UTF_8);
        return WrapperConfig.from(new ConfigLoader(Map.of(), Map.of(), false, ConfigKeys.registry()).load(conf));
    }

    private String log() throws IOException {
        Path log = dir.resolve("wrapper.log");
        return Files.exists(log) ? ThrowawayService.read(log) : "";
    }

    /** What the Service Control Manager reports, with the exit codes {@link ServiceStatus} leaves out. */
    private ScmStatus scmStatus() {
        ScmApi scm = new JnaScmApi();
        ScmHandle manager = scm.openManager(ScmApi.SC_MANAGER_CONNECT);
        try {
            ScmHandle service = scm.openService(manager, name, ScmApi.SERVICE_QUERY_STATUS);
            try {
                return scm.queryStatus(service);
            } finally {
                scm.close(service);
            }
        } finally {
            scm.close(manager);
        }
    }

    @Test(timeOut = 120_000)
    public void theRegisteredCommandLineRunsTheWrapperWhichRefusesToRunOutsideTheServiceManager() throws Exception {
        // Needs no elevation: the command line -i registers, run from here instead of by the SCM.
        WrapperConfig config = config("wrapper.app.parameter.1=park");
        Process wrapper = new ProcessBuilder(service.commandLine.build(config).arguments()).redirectErrorStream(true)
                .start();
        try {
            assertTrue(wrapper.waitFor(1, TimeUnit.MINUTES), "the wrapper did not end");
        } finally {
            wrapper.destroyForcibly();
        }
        assertEquals(wrapper.exitValue(), 3, "a service operation that failed (D23)");
        // Windows never named a service, so the bootstrap log is named after the configuration file.
        String bootstrap = Files.readString(WrapperLog.bootstrapFile(dir, "wrapper"), StandardCharsets.UTF_8);
        // The system's text is localised; the code and the hint are not.
        assertTrue(bootstrap.contains("(Win32 error 1063); --service is for the Service Control Manager"), bootstrap);
    }

    @Test(timeOut = 300_000)
    public void theServiceRunsTheApplicationUntilItIsStopped() throws Exception {
        controller.install(config("wrapper.app.parameter.1=options"));

        controller.start(name, WAIT);

        Optional<ServiceStatus> running = controller.status(name);
        assertEquals(running.map(ServiceStatus::state), Optional.of(ServiceState.RUNNING), log());
        assertTrue(running.get().pid().isPresent(), "a running service has a process");
        long wrapperPid = running.get().pid().getAsLong();
        assertTrue(waitForLog("options=["), log());
        String started = log();
        assertTrue(started.contains("running as the service '" + name + "'"), started);
        assertTrue(started.lines().anyMatch(line -> line.contains("options=[") && line.contains("-Xrs")), started);
        long applicationPid = started.lines().filter(line -> line.contains("jvm 1 started, pid ")).findFirst()
                .map(line -> Long.parseLong(line.substring(line.lastIndexOf(' ') + 1))).orElseThrow();
        assertTrue(alive(applicationPid), "the application's process is visible to the test while it runs");

        controller.stop(name, WAIT);

        ScmStatus stopped = scmStatus();
        assertEquals(stopped.currentState(), ScmApi.SERVICE_STOPPED);
        assertEquals(stopped.win32ExitCode(), 0, "a stop the SCM asked for is a success: " + log());
        String log = log();
        assertTrue(log.contains("the Service Control Manager asks the service to stop (STOP)"), log);
        assertTrue(log.contains("supervision ended STOPPED; the service has stopped"), log);
        assertFalse(alive(applicationPid), "the application is gone");
        Optional<ProcessHandle> wrapper = ProcessHandle.of(wrapperPid);
        if (wrapper.isPresent()) {
            wrapper.get().onExit().get(1, TimeUnit.MINUTES); // the process ends once STOPPED is reported
        }
        assertFalse(alive(wrapperPid), "the wrapper's process ended");
    }

    @Test(timeOut = 300_000)
    public void theServiceRunsOnlyOnceItsHttpProbePassesAndStopsGracefullyThroughTheAgent() throws Exception {
        // Phase 7's end-to-end check: readiness by an HTTP answer, and a stop through the agent.
        // It is the proof of that phase, so a missing agent jar fails it rather than skipping it.
        assertTrue(AgentJar.locate().isPresent(), "the agent's jar is not built; run the whole build (mvn verify)");
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, loopback)) {
            port = free.getLocalPort();
        }
        long serveAfter = 4_000;
        controller.install(config("wrapper.app.parameter.1=serve", "wrapper.app.parameter.2=" + port,
                "wrapper.app.parameter.3=" + serveAfter, "wrapper.readiness.type=http",
                "wrapper.readiness.http.url=http://127.0.0.1:" + port + "/health", "wrapper.startup.timeout=120",
                // Later than config()'s 2 s, so it wins; under WAIT, so a stop that times out shows why.
                "wrapper.shutdown.timeout=20"));

        long start = System.nanoTime();
        controller.start(name, WAIT);
        long startedInMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(controller.status(name).map(ServiceStatus::state), Optional.of(ServiceState.RUNNING), log());
        assertTrue(log().contains("jvm 1 is ready (http)"), log());
        // The application answers 503 for its first 4 s: the service was starting until then. The order of
        // "serving" and "ready" in the log is not asserted: the application's output reaches the log on a
        // thread of its own, so a check may pass before the line is written.
        assertTrue(startedInMs >= serveAfter, "the service was starting until then: " + startedInMs + " ms");
        assertTrue(waitForLog("the agent of jvm 1 has connected"), log());

        long stop = System.nanoTime();
        controller.stop(name, WAIT);
        long stoppedInMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stop);

        ScmStatus stopped = scmStatus();
        assertEquals(stopped.currentState(), ScmApi.SERVICE_STOPPED, log());
        assertEquals(stopped.win32ExitCode(), 0, log());
        String log = log();
        assertTrue(stoppedInMs < 20_000, "within the 20 s stop budget: " + stoppedInMs + " ms; " + log);
        assertTrue(log.contains("stopping jvm 1: agent, waiting up to "), log);
        assertTrue(log.contains("the agent of jvm 1 is ending the application"), log);
        assertTrue(log.contains("| shutdown hook ran"), "the application's shutdown hooks ran: " + log);
        assertTrue(log.contains("jvm 1 exited with code 0"), "System.exit(0) through the agent: " + log);
        assertFalse(log.contains("terminating it"), log);
    }

    @Test(timeOut = 300_000)
    public void anApplicationThatEndsWithAnErrorStopsTheServiceWithItsCode() throws Exception {
        controller.install(config("wrapper.app.parameter.1=report", "wrapper.app.parameter.2=3"));

        // The application ends at once: the start sees the service stop again.
        try {
            controller.start(name, WAIT);
        } catch (ServiceException e) {
            assertTrue(e.getMessage().contains("stopped again"), e.getMessage());
        }
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (scmStatus().currentState() != ScmApi.SERVICE_STOPPED && System.nanoTime() < deadline) {
            Thread.sleep(200); // the real SCM, no event to wait on
        }

        ScmStatus stopped = scmStatus();
        assertEquals(stopped.currentState(), ScmApi.SERVICE_STOPPED, log());
        assertEquals(stopped.win32ExitCode(), Win32Errors.SERVICE_SPECIFIC_ERROR, log());
        assertEquals(stopped.serviceSpecificExitCode(), 3, log());
        assertTrue(log().contains("supervision ended STOPPED; the service reports the error code 3"), log());
    }

    @Test(timeOut = 300_000)
    public void aConfigurationThatCannotBeReadIsInTheBootstrapLog() throws Exception {
        WrapperConfig config = config("wrapper.app.parameter.1=park");
        controller.install(config);
        // Broken after the install, as an operator's edit could: the service reads it at its start.
        Files.writeString(dir.resolve("wrapper.conf"), "wrapper.ntservice.name=" + name
                + "\nwrapper.java.maxmemory=lots\n", StandardCharsets.UTF_8);

        expectThrows(ServiceException.class, () -> controller.start(name, WAIT));

        ScmStatus stopped = scmStatus();
        assertEquals(stopped.win32ExitCode(), Win32Errors.SERVICE_SPECIFIC_ERROR);
        assertEquals(stopped.serviceSpecificExitCode(), 2, "a configuration error");
        String bootstrap = Files.readString(WrapperLog.bootstrapFile(dir, name), StandardCharsets.UTF_8);
        assertTrue(bootstrap.contains("wrapper.java.maxmemory"), bootstrap);
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private boolean waitForLog(String text) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (log().contains(text)) {
                return true;
            }
            Thread.sleep(100); // the application runs in a real JVM under the real SCM
        }
        return false;
    }
}
