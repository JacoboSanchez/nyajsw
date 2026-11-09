package io.jsl.nyajsw.launch;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.jsl.nyajsw.TestConfigs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The mechanics both the {@code command} probe and the {@code command} stop strategy rely on,
 * running {@link TestApplication} in a real JVM. The probe's tests cover the timeout and the
 * interrupt; these cover what only the stop strategy uses. Each command writes its pid to a file,
 * so that a test looks at its own processes only.
 */
public class ExternalCommandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private Path dir;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-external-command").toRealPath();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        TestApplication.killAll(dir);
        TestConfigs.delete(dir);
    }

    @Test(timeOut = 120_000)
    public void itReportsTheExitCode() {
        ExternalCommand command = new ExternalCommand(TestApplication.command("report", "7"), dir);

        assertEquals(command.run(TIMEOUT), new ExternalCommand.Exited(7));
        assertEquals(command.program(), TestApplication.java());
    }

    @Test(timeOut = 120_000)
    public void aCancelKillsTheCommandWithWhatItStarted() throws Exception {
        Path pid = dir.resolve("parent.pid");
        ExternalCommand command = new ExternalCommand(TestApplication.command("pid", pid.toString(), "parent"), dir);
        CompletableFuture<Void> cancel = new CompletableFuture<>();

        CompletableFuture<ExternalCommand.Outcome> outcome = CompletableFuture.supplyAsync(
                () -> command.run(TIMEOUT, cancel));
        ProcessHandle parent = TestApplication.awaitPid(pid);
        List<ProcessHandle> started = new ArrayList<>(List.of(parent));
        while (started.size() < 2) {
            assertFalse(outcome.isDone(), "it ended by itself: " + outcome);
            started = new ArrayList<>(parent.descendants().toList());
            started.add(parent);
            Thread.sleep(50); // nothing tells when the grandchild has started; bounded by the test's timeout
        }
        cancel.complete(null);

        assertEquals(outcome.get(), new ExternalCommand.Cancelled());
        for (ProcessHandle process : started) {
            process.onExit().get(30, TimeUnit.SECONDS); // a kill takes effect a moment later
        }
    }

    @Test(timeOut = 120_000)
    public void aCancelThatFailsCountsAsACancel() throws Exception {
        Path pid = dir.resolve("park.pid");
        ExternalCommand command = new ExternalCommand(TestApplication.command("pid", pid.toString(), "park"), dir);
        CompletableFuture<Void> cancel = new CompletableFuture<>();

        CompletableFuture<ExternalCommand.Outcome> outcome = CompletableFuture.supplyAsync(
                () -> command.run(TIMEOUT, cancel));
        ProcessHandle park = TestApplication.awaitPid(pid);
        cancel.completeExceptionally(new IllegalStateException("lost track"));

        assertEquals(outcome.get(), new ExternalCommand.Cancelled());
        park.onExit().get(30, TimeUnit.SECONDS);
    }

    @Test
    public void aCancelledRunDoesNotStartTheCommand() {
        // A program that does not exist would be NotStarted if it were tried.
        ExternalCommand command = new ExternalCommand(List.of(dir.resolve("missing.exe").toString()), dir);

        assertEquals(command.run(TIMEOUT, CompletableFuture.completedFuture(null)), new ExternalCommand.Cancelled());
    }

    @Test
    public void aProgramThatCannotBeRunIsReportedWithoutItsArguments() {
        String missing = dir.resolve("missing.exe").toString();
        ExternalCommand command = new ExternalCommand(List.of(missing, "--token", "s3cr3t"), dir);

        ExternalCommand.Outcome outcome = command.run(TIMEOUT);

        assertTrue(outcome instanceof ExternalCommand.NotStarted, outcome.toString());
        assertFalse(outcome.toString().contains("s3cr3t"), outcome.toString());
    }

    @Test
    public void everyOutcomeHasAMessageThatNamesTheProgramAsGiven() {
        Duration timeout = Duration.ofSeconds(10);
        String name = "the stop command x";

        assertEquals(ExternalCommand.describe(new ExternalCommand.Exited(3), name, timeout),
                "the stop command x exited with 3");
        assertEquals(ExternalCommand.describe(new ExternalCommand.NotStarted("error=2"), name, timeout),
                "cannot run the stop command x: error=2");
        assertEquals(ExternalCommand.describe(new ExternalCommand.TimedOut(), name, timeout),
                "the stop command x did not end within 10 s; killed");
        assertEquals(ExternalCommand.describe(new ExternalCommand.Interrupted(), name, timeout),
                "the stop command x was interrupted; killed");
        assertEquals(ExternalCommand.describe(new ExternalCommand.Cancelled(), name, timeout),
                "the stop command x was cancelled");
    }

    @Test
    public void aCommandNeedsAProgramAndATimeThatIsNotNegative() {
        expectThrows(IllegalArgumentException.class, () -> new ExternalCommand(List.of(), dir));
        expectThrows(IllegalArgumentException.class,
                () -> new ExternalCommand(List.of("x"), dir).run(Duration.ofMillis(-1)));
    }

    @Test(timeOut = 120_000)
    public void aTimeoutTooLongForNanosecondsIsNoError() {
        ExternalCommand command = new ExternalCommand(TestApplication.command("report", "0"), dir);

        assertEquals(command.run(Duration.ofSeconds(Long.MAX_VALUE)), new ExternalCommand.Exited(0));
    }
}
