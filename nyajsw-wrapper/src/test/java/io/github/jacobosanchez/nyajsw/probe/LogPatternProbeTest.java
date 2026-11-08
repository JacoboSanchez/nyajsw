package io.github.jacobosanchez.nyajsw.probe;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.TestConfigs;
import io.github.jacobosanchez.nyajsw.config.ProbeConfig;
import io.github.jacobosanchez.nyajsw.launch.OutputLines;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class LogPatternProbeTest {

    private static final Pattern STARTED = Pattern.compile("Started .* in \\d+");

    private Path dir;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-log-probe").toRealPath();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        TestConfigs.delete(dir);
    }

    /** A liveness check: each one needs a new match. */
    private static ProbeContext liveness(FakeRunningApplication application) {
        return new ProbeContext(application, ProbeContext.Purpose.LIVENESS);
    }

    private static ProbeContext readiness(FakeRunningApplication application) {
        return new ProbeContext(application, ProbeContext.Purpose.READINESS);
    }

    private void append(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    public void capturedOutputPassesOnceAMatchingLineHasComeSinceTheLaunch() {
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.empty());
        application.output().add("Starting app");

        assertEquals(probe.check(liveness(application)),
                ProbeResult.fail("none of the 1 new lines of jvm 1 matches the pattern"));
        application.output().add("Started App in 4 seconds");
        assertEquals(probe.check(liveness(application)),
                ProbeResult.pass("jvm 1 logged a line matching the pattern"));
    }

    @Test
    public void eachCheckLooksOnlyAtTheLinesThatCameSinceTheLastOneAsALivenessHeartbeat() {
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe probe = new LogPatternProbe(Pattern.compile("heartbeat"), Optional.empty());

        application.output().add("heartbeat 1");
        assertTrue(probe.check(liveness(application)).passed());
        assertFalse(probe.check(liveness(application)).passed(), "no new heartbeat");
        application.output().add("other");
        application.output().add("heartbeat 2");
        assertTrue(probe.check(liveness(application)).passed());
    }

    @Test
    public void asReadinessAMatchKeepsPassingWhileAnotherProbeOfTheListFails() {
        // wrapper.readiness.type=log,http: the start-up line comes once, often before the HTTP port answers.
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe log = new LogPatternProbe(STARTED, Optional.empty());
        AtomicBoolean httpUp = new AtomicBoolean();
        ProbeRunner readinessProbes = new ProbeRunner(List.of(new ProbeRegistry.NamedProbe("log", log),
                new ProbeRegistry.NamedProbe("http", context -> httpUp.get() ? ProbeResult.pass("up")
                        : ProbeResult.fail("refused"))), Duration.ofSeconds(1));
        application.output().add("Started App in 4 seconds");

        assertFalse(readinessProbes.check(readiness(application)).passed(), "http is not up yet");
        application.output().add("other");
        httpUp.set(true);
        assertTrue(readinessProbes.check(readiness(application)).passed(), "the start-up line still counts");
    }

    @Test
    public void listedAfterAFailingProbeItReadsOnSoItsLineIsNotPushedOut() {
        // wrapper.readiness.type=http,log with a chatty application: more lines follow the start-up line
        // than the output keeps before the port answers.
        FakeRunningApplication application = new FakeRunningApplication(true);
        AtomicBoolean httpUp = new AtomicBoolean();
        ProbeRunner readinessProbes = new ProbeRunner(List.of(
                new ProbeRegistry.NamedProbe("http", context -> httpUp.get() ? ProbeResult.pass("up")
                        : ProbeResult.fail("refused")),
                new ProbeRegistry.NamedProbe("log", new LogPatternProbe(STARTED, Optional.empty()))),
                Duration.ofSeconds(1));

        application.output().add("Started App in 4 seconds");
        assertEquals(readinessProbes.check(readiness(application)).detail(), "http: refused");
        for (int i = 0; i < OutputLines.MAX_LINES + 1; i++) {
            application.output().add("line " + i);
        }
        httpUp.set(true);

        assertTrue(readinessProbes.check(readiness(application)).passed());
    }

    @Test
    public void asReadinessAFileMatchKeepsPassingForItsInvocationOnly() throws IOException {
        Path file = dir.resolve("app.log");
        append(file, "");
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.of(file));
        probe.beforeLaunch();
        FakeRunningApplication first = new FakeRunningApplication(true);
        append(file, "Started App in 4 seconds\n");

        assertTrue(probe.check(readiness(first)).passed());
        assertEquals(probe.check(readiness(first)), ProbeResult.pass(file + " has a new line matching the pattern"));
        assertFalse(probe.check(liveness(first)).passed(), "liveness still needs a new line");

        probe.beforeLaunch();
        FakeRunningApplication second = new FakeRunningApplication(true);
        assertFalse(probe.check(readiness(second)).passed(), "a new invocation needs its own line");
    }

    @Test
    public void aNewInvocationIsReadFromItsOwnStart() {
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.empty());
        FakeRunningApplication first = new FakeRunningApplication(true);
        first.output().add("Started App in 4 seconds");
        assertTrue(probe.check(liveness(first)).passed());

        FakeRunningApplication second = new FakeRunningApplication(true);
        second.output().add("Started App in 5 seconds");
        assertTrue(probe.check(liveness(second)).passed(), "its first line counts, whatever was read of the first");
    }

    @Test
    public void aFileIsReadFromWhereItWasAtTheLaunch() throws IOException {
        Path file = dir.resolve("app.log");
        append(file, "Started App in 1 seconds (an earlier run)\n");
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.of(file));
        probe.beforeLaunch();

        assertEquals(probe.check(liveness(application)),
                ProbeResult.fail("none of the 0 new lines of " + file + " matches the pattern"));
        append(file, "Starting\r\n");
        assertEquals(probe.check(liveness(application)),
                ProbeResult.fail("none of the 1 new lines of " + file + " matches the pattern"));
        append(file, "Started App in 4 seconds\r\n");
        assertEquals(probe.check(liveness(application)),
                ProbeResult.pass(file + " has a new line matching the pattern"));
        assertFalse(probe.check(liveness(application)).passed(), "nothing new");
    }

    @Test
    public void aCheckThatComesLateStillSeesWhatWasAppendedSinceTheLaunch() throws IOException {
        // The liveness probe, or a log probe listed after one that failed, is first checked well after the launch.
        Path file = dir.resolve("app.log");
        append(file, "old\n");
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.of(file));
        probe.beforeLaunch();
        append(file, "Started App in 4 seconds\n");

        assertTrue(probe.check(liveness(new FakeRunningApplication(true))).passed());
    }

    @Test
    public void aLineWithoutItsEndYetIsMatchedAndCountsOnce() throws IOException {
        Path file = dir.resolve("app.log");
        append(file, "");
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.of(file));
        probe.beforeLaunch();

        append(file, "Started App");
        assertFalse(probe.check(liveness(application)).passed());
        append(file, " in 4 seconds");
        assertTrue(probe.check(liveness(application)).passed(), "the two halves are one line");
        append(file, " (done)\n");
        assertFalse(probe.check(liveness(application)).passed(), "its end makes it no new match");
    }

    @Test
    public void onlyTheStartOfAnOverlongLineIsMatched() throws IOException {
        Path file = dir.resolve("app.log");
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.of(file));
        probe.beforeLaunch();

        append(file, "x".repeat(LogPatternProbe.MAX_LINE) + " Started App in 4 seconds\n");
        assertFalse(probe.check(liveness(new FakeRunningApplication(true))).passed());
    }

    @Test
    public void aFileThatGrewTooMuchIsReadFromNearItsEnd() throws IOException {
        Path file = dir.resolve("app.log");
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.of(file));
        probe.beforeLaunch();
        String line = "Started App in 4 seconds\n" + "y".repeat(1023) + "\n";
        StringBuilder text = new StringBuilder(line); // the match is in the first line only
        while (text.length() <= LogPatternProbe.MAX_READ) {
            text.append("z".repeat(1023)).append('\n');
        }
        append(file, text.toString());

        ProbeResult result = probe.check(liveness(new FakeRunningApplication(true)));

        assertFalse(result.passed(), "the start is skipped: " + result.detail());
    }

    @Test
    public void capturedOutputTellsHowManyLinesItMissed() {
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.empty());
        for (int i = 0; i < OutputLines.MAX_LINES + 2; i++) {
            application.output().add("line " + i);
        }

        assertEquals(probe.check(liveness(application)), ProbeResult.fail("none of the " + OutputLines.MAX_LINES
                + " new lines of jvm 1 matches the pattern (2 earlier lines were no longer kept)"));
    }

    @Test
    public void aFileThatShrankIsReadAgainFromItsStart() throws IOException {
        Path file = dir.resolve("app.log");
        append(file, "x".repeat(200) + "\n");
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.of(file));
        probe.beforeLaunch();
        probe.check(liveness(application));

        Files.writeString(file, "Started App in 4 seconds\n", StandardCharsets.UTF_8); // rotated
        assertTrue(probe.check(liveness(application)).passed());
    }

    @Test
    public void aFileThatDoesNotExistYetIsReadWholeWhenItAppears() throws IOException {
        Path file = dir.resolve("logs/app.log");
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.of(file));
        probe.beforeLaunch();

        assertEquals(probe.check(liveness(application)), ProbeResult.fail(file + " does not exist"));
        Files.createDirectories(file.getParent());
        append(file, "Started App in 4 seconds\n");
        assertTrue(probe.check(liveness(application)).passed());
    }

    @Test
    public void theRegistryBuildsItFromTheConfiguration() throws IOException {
        ProbeConfig config = TestConfigs.load(dir, "wrapper.readiness.log.pattern=ready").probes();
        FakeRunningApplication application = new FakeRunningApplication(true);
        application.output().add("we are ready");

        Probe probe = ProbeRegistry.defaults().create(List.of("log"), config).get(0).probe();

        assertTrue(probe.check(liveness(application)).passed());
        assertEquals(expectThrows(IllegalArgumentException.class,
                () -> LogPatternProbe.from(TestConfigs.load(dir).probes())).getMessage(),
                "the log probe needs wrapper.readiness.log.pattern");
    }
}
