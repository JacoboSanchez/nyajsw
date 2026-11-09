package io.jsl.nyajsw.launch;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.jsl.nyajsw.TestConfigs;

import java.io.IOException;
import java.net.URISyntaxException;
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
 * interrupt; these cover what only the stop strategy uses.
 */
public class ExternalCommandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private Path dir;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-external-command").toRealPath();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        TestConfigs.delete(dir);
    }

    static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    static List<String> testApplication(String... arguments) throws URISyntaxException {
        String classes = Path.of(TestApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        List<String> command = new ArrayList<>(List.of(java(), "-cp", classes, TestApplication.class.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    @Test(timeOut = 120_000)
    public void itReportsTheExitCode() throws Exception {
        ExternalCommand command = new ExternalCommand(testApplication("report", "7"), dir);

        assertEquals(command.run(TIMEOUT), new ExternalCommand.Exited(7));
        assertEquals(command.program(), java());
    }

    @Test(timeOut = 120_000)
    public void aCancelKillsTheCommandWithWhatItStarted() throws Exception {
        ExternalCommand command = new ExternalCommand(testApplication("parent"), dir);
        CompletableFuture<Void> cancel = new CompletableFuture<>();

        CompletableFuture<ExternalCommand.Outcome> outcome = CompletableFuture.supplyAsync(
                () -> command.run(TIMEOUT, cancel));
        List<ProcessHandle> started = List.of();
        while (started.size() < 2) {
            assertFalse(outcome.isDone(), "it ended by itself: " + outcome);
            started = ProcessHandle.current().descendants().toList();
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
        ExternalCommand command = new ExternalCommand(testApplication("park"), dir);
        CompletableFuture<Void> cancel = new CompletableFuture<>();

        CompletableFuture<ExternalCommand.Outcome> outcome = CompletableFuture.supplyAsync(
                () -> command.run(TIMEOUT, cancel));
        while (ProcessHandle.current().descendants().findAny().isEmpty()) {
            Thread.sleep(50); // nothing tells when the command has started
        }
        cancel.completeExceptionally(new IllegalStateException("lost track"));

        assertEquals(outcome.get(), new ExternalCommand.Cancelled());
        for (ProcessHandle left : ProcessHandle.current().descendants().toList()) {
            left.onExit().get(30, TimeUnit.SECONDS);
        }
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
    public void aCommandNeedsAProgramAndATimeThatIsNotNegative() {
        expectThrows(IllegalArgumentException.class, () -> new ExternalCommand(List.of(), dir));
        expectThrows(IllegalArgumentException.class,
                () -> new ExternalCommand(List.of("x"), dir).run(Duration.ofMillis(-1)));
    }
}
