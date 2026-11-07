package io.github.jacobosanchez.nyajsw.log;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import io.github.jacobosanchez.nyajsw.TestConfigs;
import io.github.jacobosanchez.nyajsw.config.LineFormat;
import io.github.jacobosanchez.nyajsw.config.RollMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.ErrorManager;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class RollingFileSinkTest {

    private Path dir;
    private MutableClock clock;
    private final List<String> errors = new ArrayList<>();
    private final List<Duration> pauses = new ArrayList<>();
    private final List<Path> moves = new ArrayList<>();
    private final List<Path> deletes = new ArrayList<>();
    /** Which moves and deletes fail, by the file they act on; none by default. */
    private Predicate<Path> failing = from -> false;
    /** The monotonic ticker, in nanoseconds. */
    private long nanos;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-roll").toRealPath();
        clock = new MutableClock(Instant.parse("2026-11-07T09:05:03Z"));
        errors.clear();
        pauses.clear();
        moves.clear();
        deletes.clear();
        failing = from -> false;
        nanos = 0;
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        TestConfigs.delete(dir);
    }

    /** A sink writing each message and {@code \n}: a five-letter message is six bytes. */
    private RollingFileSink sink(String name, long maxSize, int maxFiles) throws IOException {
        return sink(new RollingFileSink.Settings(dir.resolve(name), maxSize, maxFiles, StandardCharsets.UTF_8));
    }

    private RollingFileSink sink(String name, long maxSize, RollMode mode, int maxDays) throws IOException {
        return sink(new RollingFileSink.Settings(dir.resolve(name), maxSize, 0, StandardCharsets.UTF_8,
                Optional.of(mode), maxDays));
    }

    private RollingFileSink sink(RollingFileSink.Settings settings) throws IOException {
        RollingFileSink sink = RollingFileSink.open(settings,
                new LineFormatter(new LineFormat(List.of(LineFormat.Field.MESSAGE), "\n"), ZoneOffset.UTC),
                Level.ALL, clock, text -> text.replace("hunter2", "****"),
                new RollingFileSink.Seams(
                        (from, to) -> {
                            moves.add(from);
                            fail(from);
                            RollingFileSink.Seams.REAL.mover().move(from, to);
                        },
                        file -> {
                            deletes.add(file);
                            fail(file);
                            Files.deleteIfExists(file);
                        },
                        pauses::add, () -> nanos));
        sink.setErrorManager(new ErrorManager() {
            @Override
            public synchronized void error(String message, Exception error, int code) {
                errors.add(message + (error == null ? "" : " / " + error));
            }
        });
        return sink;
    }

    private void fail(Path file) throws AccessDeniedException {
        if (failing.test(file)) {
            throw new AccessDeniedException(file + " (hunter2)");
        }
    }

    private void advance(Duration duration) {
        nanos += duration.toNanos();
    }

    private static void write(RollingFileSink sink, String... messages) {
        for (String message : messages) {
            sink.publish(new LogRecord(Level.INFO, message));
        }
    }

    /** Writes {@code messages} as logged at {@code time}, as the log stamps them from its clock. */
    private static void writeAt(RollingFileSink sink, String time, String... messages) {
        for (String message : messages) {
            LogRecord record = new LogRecord(Level.INFO, message);
            record.setInstant(Instant.parse(time));
            sink.publish(record);
        }
    }

    private String read(String name) throws IOException {
        return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    public void theJvmModeRollsAtTheStartAndBeforeEveryRestartedApplication() throws IOException {
        Files.writeString(dir.resolve("wrapper.log"), "last run\n");
        try (RollingFileSink sink = sink("wrapper.log", 0, RollMode.JVM, -1)) {
            write(sink, "start");
            sink.applicationStarting(1); // the first application shares the wrapper's file
            write(sink, "jvm 1");
            sink.applicationStarting(2);
            write(sink, "jvm 2");
        }

        assertEquals(read("wrapper.log"), "jvm 2\n");
        assertEquals(read("wrapper.log.1"), "start\njvm 1\n");
        assertEquals(read("wrapper.log.2"), "last run\n");
    }

    @Test
    public void theWrapperModeRollsAtTheStartOnly() throws IOException {
        Files.writeString(dir.resolve("wrapper.log"), "last run\n");
        try (RollingFileSink sink = sink("wrapper.log", 0, RollMode.WRAPPER, -1)) {
            write(sink, "start");
            sink.applicationStarting(2);
            write(sink, "jvm 2");
        }

        assertEquals(read("wrapper.log"), "start\njvm 2\n");
        assertEquals(read("wrapper.log.1"), "last run\n");
    }

    @Test
    public void anEmptyFileIsNotRolledAtTheStart() throws IOException {
        Files.writeString(dir.resolve("wrapper.log"), "");
        try (RollingFileSink sink = sink("wrapper.log", 0, RollMode.JVM, -1)) {
            write(sink, "start");
        }

        assertEquals(read("wrapper.log"), "start\n");
        assertFalse(Files.exists(dir.resolve("wrapper.log.1")));
    }

    @Test
    public void theDateModeStartsAFileForEachDayOfOutputAndRollsBySizeWithinTheDay() throws IOException {
        try (RollingFileSink sink = sink("w_YYYYMMDD.log", 12, RollMode.DATE, -1)) {
            writeAt(sink, "2026-11-07T09:00:00Z", "aaaaa", "bbbbb", "ccccc");
            writeAt(sink, "2026-11-09T00:00:01Z", "ddddd"); // nothing on the 8th: no file

            assertEquals(sink.file(), dir.resolve("w_20261109.log"));
        }

        assertEquals(read("w_20261107.log"), "ccccc\n");
        assertEquals(read("w_20261107.log.1"), "aaaaa\nbbbbb\n");
        assertEquals(read("w_20261109.log"), "ddddd\n");
        assertFalse(Files.exists(dir.resolve("w_20261108.log")));
    }

    @Test
    public void aLateRecordOfThePreviousDayStaysInTheCurrentDaysFile() throws IOException {
        try (RollingFileSink sink = sink("w_YYYYMMDD.log", 0, RollMode.DATE, -1)) {
            writeAt(sink, "2026-11-08T00:00:00.001Z", "after");
            writeAt(sink, "2026-11-07T23:59:59.999Z", "late");
        }

        assertEquals(read("w_20261108.log"), "after\nlate\n");
        assertEquals(read("w_20261107.log"), "", "the start day's file, opened when the sink was");
    }

    @Test
    public void maxdaysZeroKeepsOnlyTheDayWrittenTo() throws IOException {
        Files.writeString(dir.resolve("w_20261106.log"), "yesterday\n");
        Files.createDirectory(dir.resolve("w_20261101.log")); // shaped like ours, but not a file
        try (RollingFileSink sink = sink("w_YYYYMMDD.log", 0, RollMode.DATE, 0)) {
            writeAt(sink, "2026-11-07T10:00:00Z", "today");
        }

        assertFalse(Files.exists(dir.resolve("w_20261106.log")));
        assertTrue(Files.isDirectory(dir.resolve("w_20261101.log")));
        assertEquals(deletes, List.of(dir.resolve("w_20261106.log")));
        assertEquals(read("w_20261107.log"), "today\n");
    }

    @Test
    public void theNoneModeNeverRollsAndSizeRollsBySizeOnly() throws IOException {
        try (RollingFileSink sink = sink("wrapper.log", 6, RollMode.NONE, -1)) {
            write(sink, "aaaaa", "bbbbb");
            sink.applicationStarting(2);
        }
        assertEquals(read("wrapper.log"), "aaaaa\nbbbbb\n");
        assertFalse(Files.exists(dir.resolve("wrapper.log.1")));

        Files.delete(dir.resolve("wrapper.log"));
        Files.writeString(dir.resolve("wrapper.log"), "last run\n");
        try (RollingFileSink sink = sink("wrapper.log", 20, RollMode.SIZE, -1)) {
            write(sink, "aaaaa");
            sink.applicationStarting(2);
            write(sink, "bbbbb");
        }
        assertEquals(read("wrapper.log"), "bbbbb\n");
        assertEquals(read("wrapper.log.1"), "last run\naaaaa\n", "no roll at the start nor for jvm 2");
    }

    @Test
    public void anApplicationStartingAfterCloseChangesNothing() throws IOException {
        RollingFileSink sink = sink("wrapper.log", 0, RollMode.JVM, -1);
        write(sink, "aaaaa");
        sink.close();
        sink.applicationStarting(2);

        assertEquals(read("wrapper.log"), "aaaaa\n");
        assertFalse(Files.exists(dir.resolve("wrapper.log.1")));
    }

    @Test
    public void maxdaysDeletesTheFilesOfOlderDaysWithTheirCopies() throws IOException {
        for (String old : List.of("w_20261101.log", "w_20261101.log.1", "w_20261104.log", "w_20261105.log.3")) {
            Files.writeString(dir.resolve(old), "old\n");
        }
        Files.writeString(dir.resolve("other_20261101.log"), "not ours\n");
        try (RollingFileSink sink = sink("w_YYYYMMDD.log", 0, RollMode.DATE, 2)) {
            // the clock says 2026-11-07: the 5th, 6th and 7th are kept
            assertFalse(Files.exists(dir.resolve("w_20261101.log")));
            assertFalse(Files.exists(dir.resolve("w_20261101.log.1")));
            assertFalse(Files.exists(dir.resolve("w_20261104.log")));
            assertTrue(Files.exists(dir.resolve("w_20261105.log.3")));

            writeAt(sink, "2026-11-08T10:00:00Z", "aaaaa"); // a new day deletes the 5th
        }

        assertFalse(Files.exists(dir.resolve("w_20261105.log.3")));
        assertTrue(Files.exists(dir.resolve("w_20261107.log")));
        assertEquals(read("w_20261108.log"), "aaaaa\n");
        assertEquals(read("other_20261101.log"), "not ours\n");
    }

    @Test
    public void aRecordThatWouldPassTheLimitRollsTheFileFirstAndTheOldestCopiesGo() throws IOException {
        try (RollingFileSink sink = sink("wrapper.log", 12, 2)) {
            write(sink, "aaaaa", "bbbbb", "ccccc", "ddddd", "eeeee", "fffff", "ggggg");
        }

        assertEquals(read("wrapper.log"), "ggggg\n");
        assertEquals(read("wrapper.log.1"), "eeeee\nfffff\n");
        assertEquals(read("wrapper.log.2"), "ccccc\nddddd\n");
        assertFalse(Files.exists(dir.resolve("wrapper.log.3")), "maxfiles=2 keeps two copies");
    }

    @Test
    public void withoutAMaximumEveryCopyIsKept() throws IOException {
        try (RollingFileSink sink = sink("wrapper.ROLLNUM.log", 6, 0)) {
            write(sink, "aaaaa", "bbbbb", "ccccc", "ddddd");
        }

        assertEquals(read("wrapper.log"), "ddddd\n");
        assertEquals(read("wrapper.1.log"), "ccccc\n");
        assertEquals(read("wrapper.2.log"), "bbbbb\n");
        assertEquals(read("wrapper.3.log"), "aaaaa\n");
    }

    @Test
    public void aMaximumSizeOfZeroNeverRolls() throws IOException {
        try (RollingFileSink sink = sink("wrapper.log", 0, 2)) {
            write(sink, "aaaaa", "bbbbb", "ccccc");
        }

        assertEquals(read("wrapper.log"), "aaaaa\nbbbbb\nccccc\n");
        assertFalse(Files.exists(dir.resolve("wrapper.log.1")));
    }

    @Test
    public void aRecordLargerThanTheLimitIsWrittenToAnEmptyFileWithoutRollingIt() throws IOException {
        try (RollingFileSink sink = sink("wrapper.log", 4, 2)) {
            write(sink, "aaaaa", "bbbbb");
        }

        assertEquals(read("wrapper.log"), "bbbbb\n");
        assertEquals(read("wrapper.log.1"), "aaaaa\n");
        assertFalse(Files.exists(dir.resolve("wrapper.log.2")), "the empty file did not roll");
    }

    @Test
    public void anExistingFileIsAppendedToAndCountsTowardsTheLimit() throws IOException {
        Files.writeString(dir.resolve("wrapper.log"), "earlier\n");
        try (RollingFileSink sink = sink("wrapper.log", 12, 2)) {
            write(sink, "aaaaa");
        }

        assertEquals(read("wrapper.log"), "aaaaa\n");
        assertEquals(read("wrapper.log.1"), "earlier\n");
    }

    @Test
    public void theFileAndItsDirectoriesAreCreated() throws IOException {
        try (RollingFileSink sink = sink("logs/deep/wrapper.log", 0, 0)) {
            write(sink, "aaaaa");

            assertEquals(sink.file(), dir.resolve("logs/deep/wrapper.log"));
        }

        assertEquals(read("logs/deep/wrapper.log"), "aaaaa\n");
    }

    @Test
    public void aMoveThatFailsAFewTimesIsRetried() throws IOException {
        Path current = dir.resolve("wrapper.log");
        int[] failures = {2};
        failing = from -> from.equals(current) && failures[0]-- > 0;
        try (RollingFileSink sink = sink("wrapper.log", 6, 2)) {
            write(sink, "aaaaa", "bbbbb");
        }

        assertEquals(read("wrapper.log"), "bbbbb\n");
        assertEquals(read("wrapper.log.1"), "aaaaa\n");
        assertEquals(pauses, List.of(RollingFileSink.PAUSE, RollingFileSink.PAUSE));
        assertEquals(errors, List.of());
    }

    @Test
    public void aFileThatStaysLockedIsWrittenOnReportedMaskedAndRolledLaterWithoutLosingCopies() throws IOException {
        Files.writeString(dir.resolve("wrapper.log.1"), "one\n");
        Files.writeString(dir.resolve("wrapper.log.2"), "two\n");
        Path current = dir.resolve("wrapper.log");
        failing = current::equals;
        try (RollingFileSink sink = sink("wrapper.log", 6, 3)) {
            write(sink, "aaaaa", "bbbbb");
            assertEquals(moves.stream().filter(current::equals).count(), RollingFileSink.ATTEMPTS);
            assertEquals(errors.size(), 1, errors.toString());
            assertTrue(errors.get(0).startsWith("cannot roll the log file " + current + "; writing on in it,"
                    + " trying again in 60 s: java.nio.file.AccessDeniedException:"), errors.get(0));
            assertFalse(errors.get(0).contains("hunter2"), errors.get(0));

            write(sink, "ccccc"); // within the minute: no new attempt
            assertEquals(moves.stream().filter(current::equals).count(), RollingFileSink.ATTEMPTS);

            clock.advance(Duration.ofDays(-1)); // the system clock set back does not hold rolling up
            advance(RollingFileSink.RETRY_ROLL_AFTER);
            write(sink, "ddddd"); // fails again; copy 1 is free, so nothing else moves
            advance(RollingFileSink.RETRY_ROLL_AFTER);
            failing = from -> false;
            write(sink, "eeeee");
        }

        assertEquals(read("wrapper.log"), "eeeee\n");
        assertEquals(read("wrapper.log.1"), "aaaaa\nbbbbb\nccccc\nddddd\n");
        assertEquals(read("wrapper.log.2"), "one\n");
        assertEquals(read("wrapper.log.3"), "two\n");
    }

    @DataProvider
    public Object[][] lockedCopies() {
        return new Object[][] {{"wrapper.log.1"}, {"wrapper.log.2"}};
    }

    @Test(dataProvider = "lockedCopies")
    public void aLockedCopyCostsNoOtherCopyWhileTheRollIsRetried(String locked) throws IOException {
        Files.writeString(dir.resolve("wrapper.log.1"), "one\n");
        Files.writeString(dir.resolve("wrapper.log.2"), "two\n");
        Files.writeString(dir.resolve("wrapper.log.3"), "three\n");
        Path copy = dir.resolve(locked);
        failing = copy::equals;
        try (RollingFileSink sink = sink("wrapper.log", 6, 3)) {
            write(sink, "aaaaa", "bbbbb"); // the first roll deletes copy 3 and stops at the locked copy
            advance(RollingFileSink.RETRY_ROLL_AFTER);
            write(sink, "ccccc"); // still locked: fills only the gap, deletes nothing
            advance(RollingFileSink.RETRY_ROLL_AFTER);
            failing = file -> false;
            write(sink, "ddddd");
        }

        assertEquals(deletes, List.of(dir.resolve("wrapper.log.3")));
        assertEquals(read("wrapper.log"), "ddddd\n");
        assertEquals(read("wrapper.log.1"), "aaaaa\nbbbbb\nccccc\n");
        assertEquals(read("wrapper.log.2"), "one\n");
        assertEquals(read("wrapper.log.3"), "two\n");
        assertEquals(errors.size(), 2, errors.toString());
    }

    @Test
    public void aDeleteThatFailsAFewTimesIsRetried() throws IOException {
        Files.writeString(dir.resolve("wrapper.log.1"), "one\n");
        Path oldest = dir.resolve("wrapper.log.1");
        int[] failures = {3};
        failing = file -> file.equals(oldest) && failures[0]-- > 0;
        try (RollingFileSink sink = sink("wrapper.log", 6, 1)) {
            write(sink, "aaaaa", "bbbbb");
        }

        assertEquals(deletes, List.of(oldest, oldest, oldest, oldest));
        assertEquals(read("wrapper.log"), "bbbbb\n");
        assertEquals(read("wrapper.log.1"), "aaaaa\n", "maxfiles=1 keeps one copy");
        assertEquals(errors, List.of());
    }

    @Test
    public void aCurrentFileRemovedWhileOpenIsNotRolledButStartedAgain() throws IOException {
        try (RollingFileSink sink = sink("wrapper.log", 6, 2)) {
            write(sink, "aaaaa");
            try {
                Files.delete(dir.resolve("wrapper.log"));
            } catch (IOException e) {
                throw new SkipException("this platform does not delete an open file: " + e);
            }
            write(sink, "bbbbb");
        }

        assertEquals(read("wrapper.log"), "bbbbb\n");
        assertFalse(Files.exists(dir.resolve("wrapper.log.1")));
        assertEquals(errors, List.of());
    }

    @Test
    public void recordsAfterCloseAreDropped() throws IOException {
        RollingFileSink sink = sink("wrapper.log", 0, 0);
        write(sink, "aaaaa");
        sink.close();
        write(sink, "bbbbb");
        sink.flush();

        assertEquals(read("wrapper.log"), "aaaaa\n");
        assertEquals(errors, List.of());
    }

    @Test
    public void aRecordThatCannotBeFormattedIsReportedAndSkipped() throws IOException {
        try (RollingFileSink sink = sink("wrapper.log", 0, 0)) {
            LogRecord broken = new LogRecord(Level.INFO, "x");
            broken.setThrown(new IllegalStateException() {
                private static final long serialVersionUID = 1L;

                @Override
                public void printStackTrace(java.io.PrintWriter writer) {
                    throw new UnsupportedOperationException("no trace");
                }
            });
            sink.publish(broken);
            write(sink, "aaaaa");
        }

        assertEquals(read("wrapper.log"), "aaaaa\n");
        assertEquals(errors.size(), 1, errors.toString());
    }

    /** A clock that tests move by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
