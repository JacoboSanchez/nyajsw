package io.github.jacobosanchez.nyajsw.log;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.config.DebugDetail;
import io.github.jacobosanchez.nyajsw.config.LogLevel;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class WrapperLogTest {

    private static final List<LogLevel> MESSAGE_LEVELS = List.of(LogLevel.FATAL, LogLevel.ERROR, LogLevel.STATUS,
            LogLevel.INFO, LogLevel.DEBUG);

    private ByteArrayOutputStream bytes;
    private PrintStream out;

    @BeforeMethod
    public void setUp() {
        bytes = new ByteArrayOutputStream();
        out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private String text() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private List<String> lines() {
        return text().lines().toList();
    }

    @DataProvider
    public Object[][] thresholds() {
        return new Object[][] {{LogLevel.NONE}, {LogLevel.FATAL}, {LogLevel.ERROR}, {LogLevel.STATUS},
                {LogLevel.INFO}, {LogLevel.DEBUG}};
    }

    /** LogLevel lists levels from least to most verbose, so the threshold passes itself and everything before it. */
    @Test(dataProvider = "thresholds")
    public void aThresholdPassesItsOwnLevelAndTheMoreSevereOnes(LogLevel threshold) {
        List<String> expected = new ArrayList<>();
        try (WrapperLog log = WrapperLog.console(out, threshold)) {
            for (LogLevel level : MESSAGE_LEVELS) {
                log.log(level, level.name());
                boolean passes = level.ordinal() <= threshold.ordinal();
                assertEquals(log.isEnabled(level), passes, level + " at " + threshold);
                if (passes) {
                    expected.add("wrapper  | " + level.name());
                }
            }
        }

        assertEquals(lines(), expected);
    }

    @Test
    public void linesUseYajswsDefaultConsoleLayoutAndThePlatformSeparator() {
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            log.info("started");
        }

        assertEquals(text(), "wrapper  | started" + System.lineSeparator());
    }

    @Test
    public void sourceViewsNameTheirSourceAndShareTheSinks() {
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            WrapperLog jvm = log.forSource("jvm 1");
            log.info("launching");
            jvm.info("Hello from the application");
            jvm.debug("hidden by the shared threshold");

            assertEquals(jvm.source(), "jvm 1");
            assertEquals(log.source(), WrapperLog.WRAPPER_SOURCE);
        }

        assertEquals(lines(), List.of("wrapper  | launching", "jvm 1    | Hello from the application"));
    }

    @Test
    public void blankSourceIsRejected() {
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            expectThrows(IllegalArgumentException.class, () -> log.forSource(" "));
        }
    }

    @Test
    public void suppliedMessagesAreOnlyBuiltWhenEnabled() {
        AtomicInteger built = new AtomicInteger();
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            log.debug(() -> "d" + built.incrementAndGet());
            log.log(LogLevel.INFO, () -> "i" + built.incrementAndGet());
        }

        assertEquals(built.get(), 1);
        assertEquals(lines(), List.of("wrapper  | i1"));
    }

    @Test
    public void attachedErrorsAreWrittenWithTheirStackTrace() {
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            log.error("could not start", new IllegalStateException("boom"));
        }

        assertTrue(text().startsWith("wrapper  | could not start" + System.lineSeparator()
                + "java.lang.IllegalStateException: boom" + System.lineSeparator()), text());
        assertTrue(lines().get(2).trim().startsWith("at "), text());
    }

    @Test
    public void aRecordThatCannotBeFormattedDoesNotFailTheCaller() {
        Throwable hostile = new IllegalStateException() {
            private static final long serialVersionUID = 1L;

            @Override
            public String toString() {
                throw new UnsupportedOperationException("no text");
            }
        };
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            log.error("bad", hostile); // reported to the sink's error manager on System.err
            log.info("next");
        }

        assertEquals(lines(), List.of("wrapper  | next"));
    }

    @Test
    public void noneIsNotAMessageLevel() {
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            expectThrows(IllegalArgumentException.class, () -> log.log(LogLevel.NONE, "x"));
            expectThrows(IllegalArgumentException.class, () -> log.isEnabled(LogLevel.NONE));
        }
    }

    @Test
    public void debugMessagesAreWrittenUpToTheLogsDebugDetail() {
        try (WrapperLog log = WrapperLog.console(out, LogLevel.DEBUG, DebugDetail.APPLICATION)) {
            WrapperLog jvm = log.forSource("jvm 1");
            log.debug(DebugDetail.APPLICATION, "application");
            log.debug(DebugDetail.SUPERVISION, () -> "supervision");
            log.debug("all, by default");
            log.log(LogLevel.DEBUG, "all, without a detail");
            jvm.debug(DebugDetail.APPLICATION, "the views share the detail");
            jvm.debug(DebugDetail.ALL, "hidden as well");

            assertTrue(log.isEnabled(DebugDetail.APPLICATION));
            assertFalse(log.isEnabled(DebugDetail.SUPERVISION));
            assertFalse(log.isEnabled(LogLevel.DEBUG), "a debug message without a detail has all of it");
            assertTrue(log.isEnabled(LogLevel.INFO));
        }

        assertEquals(lines(), List.of("wrapper  | application", "jvm 1    | the views share the detail"));
    }

    @Test
    public void theDebugDetailDoesNotOpenAThresholdBelowDebug() {
        AtomicInteger built = new AtomicInteger();
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO, DebugDetail.ALL)) {
            log.debug(DebugDetail.APPLICATION, () -> "built " + built.incrementAndGet());

            assertFalse(log.isEnabled(DebugDetail.APPLICATION));
        }

        assertEquals(built.get(), 0);
        assertEquals(text(), "");
    }

    @Test
    public void noDebugDetailWritesNoDebugMessage() {
        try (WrapperLog log = WrapperLog.console(out, LogLevel.DEBUG, DebugDetail.NONE)) {
            log.debug(DebugDetail.APPLICATION, "hidden");
            log.info("shown");

            expectThrows(IllegalArgumentException.class, () -> log.debug(DebugDetail.NONE, "x"));
            expectThrows(IllegalArgumentException.class, () -> log.isEnabled(DebugDetail.NONE));
        }

        assertEquals(lines(), List.of("wrapper  | shown"));
    }

    @Test
    public void closingLeavesTheStreamOpenAndStopsWriting() {
        WrapperLog log = WrapperLog.console(out, LogLevel.INFO);
        log.info("before");
        log.close();
        log.info("after");
        out.println("still open");

        assertFalse(out.checkError());
        assertEquals(lines(), List.of("wrapper  | before", "still open"));
    }

    @Test
    public void logsAreIndependentOfEachOther() {
        ByteArrayOutputStream otherBytes = new ByteArrayOutputStream();
        try (WrapperLog quiet = WrapperLog.console(new PrintStream(otherBytes, true, StandardCharsets.UTF_8),
                LogLevel.ERROR);
                WrapperLog verbose = WrapperLog.console(out, LogLevel.DEBUG)) {
            quiet.info("hidden");
            verbose.debug("shown");
        }

        assertEquals(otherBytes.size(), 0);
        assertEquals(lines(), List.of("wrapper  | shown"));
    }

    @Test
    public void globalLoggingConfigurationDoesNotReachTheLog() {
        Logger root = Logger.getLogger("");
        Level rootLevel = root.getLevel();
        List<LogRecord> seenByRoot = new ArrayList<>();
        Handler recorder = new Handler() {
            @Override
            public void publish(LogRecord record) {
                seenByRoot.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        root.addHandler(recorder);
        root.setLevel(Level.OFF);
        try (WrapperLog log = WrapperLog.console(out, LogLevel.INFO)) {
            log.info("isolated");
        } finally {
            root.removeHandler(recorder);
            root.setLevel(rootLevel);
        }

        assertTrue(seenByRoot.isEmpty(), seenByRoot.toString());
        assertEquals(lines(), List.of("wrapper  | isolated"));
    }
}
