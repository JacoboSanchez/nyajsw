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
import java.util.List;
import java.util.Optional;
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

    private static ProbeContext context(FakeRunningApplication application) {
        return new ProbeContext(application);
    }

    private void append(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    public void capturedOutputPassesOnceAMatchingLineHasComeSinceTheLaunch() {
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.empty());
        application.output().add("Starting app");

        assertEquals(probe.check(context(application)),
                ProbeResult.fail("none of the 1 new lines of jvm 1 matches the pattern"));
        application.output().add("Started App in 4 seconds");
        assertEquals(probe.check(context(application)),
                ProbeResult.pass("jvm 1 logged a line matching the pattern"));
    }

    @Test
    public void eachCheckLooksOnlyAtTheLinesThatCameSinceTheLastOneAsALivenessHeartbeat() {
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe probe = new LogPatternProbe(Pattern.compile("heartbeat"), Optional.empty());

        application.output().add("heartbeat 1");
        assertTrue(probe.check(context(application)).passed());
        assertFalse(probe.check(context(application)).passed(), "no new heartbeat");
        application.output().add("other");
        application.output().add("heartbeat 2");
        assertTrue(probe.check(context(application)).passed());
    }

    @Test
    public void aNewInvocationIsReadFromItsOwnStart() {
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.empty());
        FakeRunningApplication first = new FakeRunningApplication(true);
        first.output().add("Started App in 4 seconds");
        assertTrue(probe.check(context(first)).passed());

        FakeRunningApplication second = new FakeRunningApplication(true);
        second.output().add("Started App in 5 seconds");
        assertTrue(probe.check(context(second)).passed(), "its first line counts, whatever was read of the first");
    }

    @Test
    public void aFileIsReadFromWhereItWasAtTheLaunch() throws IOException {
        Path file = dir.resolve("app.log");
        append(file, "Started App in 1 seconds (an earlier run)\n");
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.of(file));
        probe.beforeLaunch();

        assertEquals(probe.check(context(application)),
                ProbeResult.fail("none of the 0 new lines of " + file + " matches the pattern"));
        append(file, "Starting\r\n");
        assertEquals(probe.check(context(application)),
                ProbeResult.fail("none of the 1 new lines of " + file + " matches the pattern"));
        append(file, "Started App in 4 seconds\r\n");
        assertEquals(probe.check(context(application)),
                ProbeResult.pass(file + " has a new line matching the pattern"));
        assertFalse(probe.check(context(application)).passed(), "nothing new");
    }

    @Test
    public void aCheckThatComesLateStillSeesWhatWasAppendedSinceTheLaunch() throws IOException {
        // The liveness probe, or a log probe listed after one that failed, is first checked well after the launch.
        Path file = dir.resolve("app.log");
        append(file, "old\n");
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.of(file));
        probe.beforeLaunch();
        append(file, "Started App in 4 seconds\n");

        assertTrue(probe.check(context(new FakeRunningApplication(true))).passed());
    }

    @Test
    public void aLineWithoutItsEndYetIsMatchedAndCountsOnce() throws IOException {
        Path file = dir.resolve("app.log");
        append(file, "");
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.of(file));
        probe.beforeLaunch();

        append(file, "Started App");
        assertFalse(probe.check(context(application)).passed());
        append(file, " in 4 seconds");
        assertTrue(probe.check(context(application)).passed(), "the two halves are one line");
        append(file, " (done)\n");
        assertFalse(probe.check(context(application)).passed(), "its end makes it no new match");
    }

    @Test
    public void onlyTheStartOfAnOverlongLineIsMatched() throws IOException {
        Path file = dir.resolve("app.log");
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.of(file));
        probe.beforeLaunch();

        append(file, "x".repeat(LogPatternProbe.MAX_LINE) + " Started App in 4 seconds\n");
        assertFalse(probe.check(context(new FakeRunningApplication(true))).passed());
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

        ProbeResult result = probe.check(context(new FakeRunningApplication(true)));

        assertFalse(result.passed(), "the start is skipped: " + result.detail());
    }

    @Test
    public void capturedOutputTellsHowManyLinesItMissed() {
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.empty());
        for (int i = 0; i < OutputLines.MAX_LINES + 2; i++) {
            application.output().add("line " + i);
        }

        assertEquals(probe.check(context(application)), ProbeResult.fail("none of the " + OutputLines.MAX_LINES
                + " new lines of jvm 1 matches the pattern (2 earlier lines were no longer kept)"));
    }

    @Test
    public void aFileThatShrankIsReadAgainFromItsStart() throws IOException {
        Path file = dir.resolve("app.log");
        append(file, "x".repeat(200) + "\n");
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.of(file));
        probe.beforeLaunch();
        probe.check(context(application));

        Files.writeString(file, "Started App in 4 seconds\n", StandardCharsets.UTF_8); // rotated
        assertTrue(probe.check(context(application)).passed());
    }

    @Test
    public void aFileThatDoesNotExistYetIsReadWholeWhenItAppears() throws IOException {
        Path file = dir.resolve("logs/app.log");
        FakeRunningApplication application = new FakeRunningApplication(true);
        LogPatternProbe probe = new LogPatternProbe(STARTED, Optional.of(file));
        probe.beforeLaunch();

        assertEquals(probe.check(context(application)), ProbeResult.fail(file + " does not exist"));
        Files.createDirectories(file.getParent());
        append(file, "Started App in 4 seconds\n");
        assertTrue(probe.check(context(application)).passed());
    }

    @Test
    public void theRegistryBuildsItFromTheConfiguration() throws IOException {
        ProbeConfig config = TestConfigs.load(dir, "wrapper.readiness.log.pattern=ready").probes();
        FakeRunningApplication application = new FakeRunningApplication(true);
        application.output().add("we are ready");

        Probe probe = ProbeRegistry.defaults().create(List.of("log"), config).get(0).probe();

        assertTrue(probe.check(context(application)).passed());
        assertEquals(expectThrows(IllegalArgumentException.class,
                () -> LogPatternProbe.from(TestConfigs.load(dir).probes())).getMessage(),
                "the log probe needs wrapper.readiness.log.pattern");
    }
}
