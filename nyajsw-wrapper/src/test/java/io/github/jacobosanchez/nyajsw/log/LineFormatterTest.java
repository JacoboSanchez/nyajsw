package io.github.jacobosanchez.nyajsw.log;

import static io.github.jacobosanchez.nyajsw.config.LineFormat.Field.LEVEL;
import static io.github.jacobosanchez.nyajsw.config.LineFormat.Field.MESSAGE;
import static io.github.jacobosanchez.nyajsw.config.LineFormat.Field.MILLIS;
import static io.github.jacobosanchez.nyajsw.config.LineFormat.Field.PREFIX;
import static io.github.jacobosanchez.nyajsw.config.LineFormat.Field.THREAD;
import static io.github.jacobosanchez.nyajsw.config.LineFormat.Field.TIME;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import io.github.jacobosanchez.nyajsw.config.LineFormat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.testng.annotations.Test;

public class LineFormatterTest {

    /** 2026-11-07 09:05:03.042 in UTC, so 10:05:03.042 in the zone of the tests. */
    private static final Instant TIME_OF_RECORD = Instant.parse("2026-11-07T09:05:03.042Z");
    private static final String NL = System.lineSeparator();

    private static String format(List<LineFormat.Field> fields, String separator, LogRecord record) {
        return new LineFormatter(new LineFormat(fields, separator), ZoneOffset.ofHours(1)).format(record);
    }

    private static LogRecord record(Level level, String source, String message) {
        LogRecord record = new WrapperRecord(level, message);
        record.setLoggerName(source);
        record.setInstant(TIME_OF_RECORD);
        return record;
    }

    @Test
    public void yajswsDefaultFileFormatIsLevelPrefixTimeAndMessage() {
        assertEquals(format(List.of(LEVEL, PREFIX, TIME, MESSAGE), NL, record(WrapperLevel.INFO, "wrapper", "up")),
                "INFO   | wrapper  | 2026/11/07 10:05:03 | up" + NL);
        assertEquals(format(List.of(LEVEL, PREFIX, TIME, MESSAGE), NL, record(WrapperLevel.STATUS, "jvm 1", "ready")),
                "STATUS | jvm 1    | 2026/11/07 10:05:03 | ready" + NL);
    }

    @Test
    public void theConsoleDefaultIsPrefixAndMessage() {
        assertEquals(new LineFormatter(LineFormat.CONSOLE_DEFAULT, ZoneOffset.UTC)
                .format(record(WrapperLevel.INFO, "wrapper", "started")), "wrapper  | started" + NL);
    }

    @Test
    public void millisecondsThreadAndTrailingPadding() {
        assertEquals(format(List.of(MESSAGE, MILLIS, THREAD, LEVEL), "\n", record(WrapperLevel.DEBUG, "wrapper", "m")),
                "m | 2026/11/07 10:05:03.042 | " + Thread.currentThread().getName() + " | DEBUG \n");
    }

    @Test
    public void longerPrefixesAreNotCut() {
        assertEquals(format(List.of(PREFIX, MESSAGE), "\n", record(WrapperLevel.INFO, "jvm 1234", "m")),
                "jvm 1234 | m\n");
        assertEquals(format(List.of(PREFIX, MESSAGE), "\n", record(WrapperLevel.INFO, "jvm 12345", "m")),
                "jvm 12345 | m\n");
    }

    @Test
    public void aRecordWithoutSourceOrThreadIsTheWrappers() {
        LogRecord plain = new LogRecord(WrapperLevel.ERROR, "m");
        plain.setInstant(TIME_OF_RECORD);

        assertEquals(format(List.of(PREFIX, THREAD, MESSAGE), "\n", plain), "wrapper  |  | m\n");
    }

    @Test
    public void anEmptyFirstFieldKeepsItsSeparatorSoTheFieldsStayInPlace() {
        // A blank line of application output is an empty message.
        assertEquals(format(List.of(MESSAGE, PREFIX), "\n", record(WrapperLevel.INFO, "jvm 1", "")), " | jvm 1   \n");
        assertEquals(format(List.of(THREAD, MESSAGE), "\n", new LogRecord(WrapperLevel.INFO, "m")), " | m\n");
        assertEquals(format(List.of(PREFIX, MESSAGE, LEVEL), "\n", record(WrapperLevel.INFO, "jvm 1", "")),
                "jvm 1    |  | INFO  \n");
    }

    @Test
    public void theStackTraceGoesThroughTheMask() {
        LogRecord record = record(WrapperLevel.ERROR, "wrapper", "failed");
        record.setThrown(new IllegalStateException("password hunter2 rejected"));

        String text = new LineFormatter(new LineFormat(List.of(MESSAGE), "\n"), ZoneOffset.UTC,
                trace -> trace.replace("hunter2", "****")).format(record);

        assertTrue(text.startsWith("failed\njava.lang.IllegalStateException: password **** rejected\n\tat "), text);
        assertEquals(text.indexOf('\r'), -1, "masked before the line ending is chosen");
    }

    @Test
    public void aChosenLineEndingAlsoEndsTheLinesOfTheMessageAndTheStackTrace() {
        LogRecord record = record(WrapperLevel.ERROR, "wrapper", "first" + NL + "second");
        record.setThrown(new IllegalStateException("boom"));

        String text = format(List.of(MESSAGE), "\r\n", record);

        assertTrue(text.startsWith("first\r\nsecond\r\njava.lang.IllegalStateException: boom\r\n\tat "), text);
        assertEquals(text.replace("\r\n", "").indexOf('\n'), -1, text);
        assertEquals(text.replace("\r\n", "").indexOf('\r'), -1, text);
    }
}
