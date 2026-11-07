package io.github.jacobosanchez.nyajsw.probe;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.TestConfigs;
import io.github.jacobosanchez.nyajsw.config.ProbeConfig;
import io.github.jacobosanchez.nyajsw.launch.TestApplication;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The {@code command} probe, running {@link TestApplication} in a real JVM. */
public class CommandProbeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private Path dir;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-command-probe").toRealPath();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        TestConfigs.delete(dir);
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static List<String> testApplication(String... arguments) throws URISyntaxException {
        String classes = Path.of(TestApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        List<String> command = new ArrayList<>(List.of(java(), "-cp", classes, TestApplication.class.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    @Test(timeOut = 120_000)
    public void passesOnExitCodeZeroWithItsInputClosed() throws Exception {
        // report reads its standard input to the end first: with it open, the run would time out.
        CommandProbe probe = new CommandProbe(testApplication("report", "0"), dir, TIMEOUT);

        assertEquals(probe.check(FakeRunningApplication.running()), ProbeResult.pass(java() + " exited with 0"));
    }

    @Test(timeOut = 120_000)
    public void failsOnAnyOtherExitCode() throws Exception {
        CommandProbe probe = new CommandProbe(testApplication("report", "3"), dir, TIMEOUT);

        assertEquals(probe.check(FakeRunningApplication.running()), ProbeResult.fail(java() + " exited with 3"));
    }

    @Test(timeOut = 120_000)
    public void aCommandThatOverrunsIsKilledWithWhatItStarted() throws Exception {
        // parent starts a park grandchild: both must be gone once the check has returned.
        CommandProbe probe = new CommandProbe(testApplication("parent"), dir, Duration.ofSeconds(10));

        CompletableFuture<ProbeResult> result = CompletableFuture.supplyAsync(
                () -> probe.check(FakeRunningApplication.running()));
        // Seen while they run: once the parent is killed, its child no longer counts as our descendant.
        Set<ProcessHandle> started = new HashSet<>();
        while (!result.isDone()) {
            started.addAll(ProcessHandle.current().descendants().toList());
            Thread.sleep(50); // nothing tells when the grandchild has started; bounded by the check
        }

        assertEquals(result.get(), ProbeResult.fail(java() + " did not end within 10 s; killed"));
        assertTrue(started.size() >= 2, "the command and its child: " + started);
        for (ProcessHandle process : started) {
            process.onExit().get(30, TimeUnit.SECONDS); // a kill takes effect a moment later
        }
    }

    @Test(timeOut = 120_000)
    public void anInterruptedCheckKillsTheCommandAndKeepsTheInterrupt() throws Exception {
        CommandProbe probe = new CommandProbe(testApplication("park"), dir, TIMEOUT);
        Thread.currentThread().interrupt();
        try {
            assertEquals(probe.check(FakeRunningApplication.running()),
                    ProbeResult.fail(java() + " was interrupted; killed"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        for (ProcessHandle left : ProcessHandle.current().descendants().toList()) {
            left.onExit().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    public void aProgramThatCannotBeRunFailsWithoutItsArguments() {
        String missing = dir.resolve("missing.exe").toString();
        CommandProbe probe = new CommandProbe(List.of(missing, "--token", "s3cr3t"), dir, TIMEOUT);

        ProbeResult result = probe.check(FakeRunningApplication.running());

        assertFalse(result.passed());
        assertTrue(result.detail().startsWith("cannot run " + missing + ": "), result.detail());
        assertTrue(result.detail().length() > ("cannot run " + missing + ": ").length() + 12,
                "the reason is kept: " + result.detail());
        assertFalse(result.detail().contains("s3cr3t"), result.detail());
    }

    @Test(timeOut = 120_000)
    public void theRegistryBuildsItFromTheConfigurationAndRunsItInTheWorkingDirectory() throws Exception {
        // A classpath of "." finds TestApplication only when the command runs in the classes directory.
        String classes = Path.of(TestApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString().replace('\\', '/');
        ProbeConfig config = TestConfigs.load(dir, "wrapper.working.dir=" + classes,
                "wrapper.readiness.command=\"" + java().replace('\\', '/') + "\" -cp . "
                        + TestApplication.class.getName() + " report 0").probes();

        Probe probe = ProbeRegistry.defaults().create(List.of("command"), config).get(0).probe();

        assertTrue(probe.check(FakeRunningApplication.running()).passed());
        assertEquals(expectThrows(IllegalArgumentException.class,
                () -> CommandProbe.from(TestConfigs.load(dir).probes())).getMessage(),
                "the command probe needs wrapper.readiness.command");
    }

    @Test
    public void aCommandNeedsAProgramAndATimeout() {
        expectThrows(IllegalArgumentException.class, () -> new CommandProbe(List.of(), dir, TIMEOUT));
        expectThrows(IllegalArgumentException.class, () -> new CommandProbe(List.of("x"), dir, Duration.ZERO));
    }
}
