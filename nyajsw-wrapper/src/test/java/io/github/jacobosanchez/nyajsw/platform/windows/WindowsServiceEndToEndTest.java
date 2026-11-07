package io.github.jacobosanchez.nyajsw.platform.windows;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.TestConfigs;
import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ConfigLoader;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.launch.TestApplication;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;
import io.github.jacobosanchez.nyajsw.platform.HostOs;
import io.github.jacobosanchez.nyajsw.platform.ServiceException;
import io.github.jacobosanchez.nyajsw.platform.ServiceState;
import io.github.jacobosanchez.nyajsw.platform.ServiceStatus;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.testng.ITestResult;
import org.testng.Reporter;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The wrapper run as a real service by the Service Control Manager (plan P6.5): install a throwaway
 * service whose binary path runs {@code --service} on this build's classes, start it until it is
 * {@code RUNNING} with {@link TestApplication} as the application, stop it until it is {@code
 * STOPPED}, and remove it. Excluded by default; run with {@code mvn -q verify -Pwindows-integration}
 * in an elevated shell.
 *
 * <p>The service is named {@code nyajsw-test-<random>} and removed after each test, also when it
 * fails; a failed test prints the wrapper log and the bootstrap logs into the test report. A test
 * JVM killed in between can leave one behind, with its wrapper running as {@code LocalSystem}:
 * {@code sc query state= all | findstr nyajsw-test-} finds it, {@code sc stop <name>} and {@code sc
 * delete <name>} remove it.
 */
@Test(groups = "windows-integration")
public class WindowsServiceEndToEndTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    private Path dir;
    private String name;
    private ServiceCommandLine commandLine;
    private WindowsServiceController controller;

    @BeforeMethod(alwaysRun = true)
    public void setUp() throws IOException {
        dir = null; // one instance runs every method: nothing may be left from the one before
        name = null;
        commandLine = null;
        controller = null;
        if (!HostOs.isWindows()) {
            throw new SkipException("the Service Control Manager exists on Windows only");
        }
        dir = Files.createTempDirectory("nyajsw-e2e").toRealPath();
        Files.createDirectories(dir.resolve("tmp"));
        name = "nyajsw-test-" + UUID.randomUUID().toString().substring(0, 8);
        commandLine = new ServiceCommandLine(Path.of(System.getProperty("java.home")), serviceClasspath(),
                ServiceCommandLine.nativeCharset());
        controller = new WindowsServiceController(new JnaScmApi(), new JnaLsaApi(), commandLine, StateWait.REAL);
    }

    /** The test JVM's full classpath (surefire runs it from a manifest-only jar): the wrapper, JNA, the test application. */
    private static List<Path> serviceClasspath() {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<Path> entries = Arrays.stream(classpath.split(File.pathSeparator)).filter(entry -> !entry.isBlank())
                .map(entry -> Path.of(entry).toAbsolutePath()).toList();
        assertTrue(entries.stream().anyMatch(entry -> entry.getFileName().toString().startsWith("jna-platform"))
                && entries.contains(Path.of(locationOf(WindowsServiceController.class))), "the service needs the"
                + " wrapper's classes and JNA: " + entries);
        return entries;
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown(ITestResult result) throws Exception {
        try {
            if (controller != null && name != null) {
                stopQuietly();
                try {
                    controller.remove(name);
                } catch (ServiceException e) {
                    int code = e.errorCode();
                    if (code != Win32Errors.SERVICE_DOES_NOT_EXIST && code != Win32Errors.SERVICE_MARKED_FOR_DELETE) {
                        throw e;
                    }
                }
            }
        } finally {
            if (dir != null) {
                if (!result.isSuccess()) {
                    report();
                }
                deleteDirectory();
            }
        }
    }

    /**
     * Stops the service, also one that is still pending (which takes no stop request: it is waited
     * for), and kills its process as a last resort, so that nothing holds the test's directory.
     */
    private void stopQuietly() throws InterruptedException {
        try {
            Optional<ServiceStatus> status = controller.status(name);
            if (status.isEmpty() || status.get().state() == ServiceState.STOPPED) {
                return;
            }
            if (status.get().state() == ServiceState.RUNNING) {
                controller.stop(name, WAIT);
                return;
            }
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                status = controller.status(name);
                if (status.isEmpty() || status.get().state() == ServiceState.STOPPED) {
                    return;
                }
                if (status.get().state() == ServiceState.RUNNING) {
                    controller.stop(name, WAIT);
                    return;
                }
                Thread.sleep(200); // the real SCM, no event to wait on
            }
            status.flatMap(found -> found.pid().isPresent() ? ProcessHandle.of(found.pid().getAsLong())
                    : Optional.empty()).ifPresent(process -> {
                        Reporter.log("killing the process " + process.pid() + " of " + name + ", which did not stop",
                                true);
                        process.destroyForcibly();
                    });
        } catch (ServiceException e) {
            Reporter.log("could not stop " + name + ": " + e.getMessage(), true);
        }
    }

    /**
     * Deletes the test's directory once the wrapper's process lets go of it: the JNA library it
     * unpacked there stays in use until that process has ended, which comes a moment after {@code
     * STOPPED}. One that is still held is left behind with a warning, not a failure.
     */
    private void deleteDirectory() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            try {
                TestConfigs.delete(dir);
                return;
            } catch (IOException e) {
                if (System.nanoTime() > deadline) {
                    Reporter.log("left " + dir + " behind: " + e, true);
                    return;
                }
                Thread.sleep(200); // no event says when the process has let go of its files
            }
        }
    }

    /** The logs of a failed run, into the test report and its output; one that cannot be read is said so. */
    private void report() {
        List<Path> logs = List.of(dir.resolve("wrapper.log"), WrapperLog.bootstrapFile(dir, name),
                WrapperLog.bootstrapFile(dir.resolve("tmp"), name));
        for (Path log : logs) {
            if (Files.exists(log)) {
                Reporter.log("----- " + log + " -----", true);
                try {
                    Reporter.log(read(log), true);
                } catch (IOException e) {
                    Reporter.log("cannot read it: " + e, true);
                }
            }
        }
    }

    /** {@code file} as UTF-8, the wrapper log's default encoding on Java 18 and later; bad bytes replaced. */
    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private WrapperConfig config(String... application) throws IOException {
        String classes = Path.of(locationOf(TestApplication.class)).toString().replace('\\', '/');
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

    private static String locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private String log() throws IOException {
        Path log = dir.resolve("wrapper.log");
        return Files.exists(log) ? read(log) : "";
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
        Process wrapper = new ProcessBuilder(commandLine.build(config).arguments()).redirectErrorStream(true).start();
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
