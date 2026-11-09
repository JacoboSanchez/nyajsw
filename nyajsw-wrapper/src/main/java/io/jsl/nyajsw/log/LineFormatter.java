package io.jsl.nyajsw.log;

import io.jsl.nyajsw.config.LineFormat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * Writes a record as one line in a {@link LineFormat}, followed by the stack trace of an attached
 * error. The fields are joined by {@code " | "}, an empty one too, so that every line of a format
 * has the same number of fields:
 *
 * <ul>
 *   <li>the level is padded to the longest level name ({@code STATUS});
 *   <li>the prefix, the record's source ({@code wrapper}, or {@code jvm 1} for captured application
 *       output), is padded so that the two line up, and never cut;
 *   <li>the thread is the one {@link WrapperLog} recorded;
 *   <li>times are local, {@code yyyy/MM/dd HH:mm:ss}, with {@code .SSS} for the millisecond field:
 *       YAJSW does not document its time formats, these are nyajsw's.
 * </ul>
 *
 * <p>When the format chooses a line ending other than the platform's, the line breaks inside the
 * message and the stack trace follow it too, so that one log does not mix endings.
 *
 * <p>The stack trace of an attached exception goes through a mask, the configuration's secrets for
 * a configured log, since nothing masked it before. Exception text that a caller puts into the
 * message itself is the caller's to mask, like every other message.
 */
final class LineFormatter extends Formatter {

    /** The width of the level field: the longest level name. */
    static final int LEVEL_WIDTH = "STATUS".length();

    /** The width of the prefix: {@code wrapper}, and {@code jvm} with a two-digit invocation number. */
    static final int SOURCE_WIDTH = 8;

    static final String SEPARATOR = " | ";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter MILLIS =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS", Locale.ROOT);

    private final LineFormat format;
    private final ZoneId zone;
    private final UnaryOperator<String> traceMask;

    /** A formatter that writes stack traces as they are. */
    LineFormatter(LineFormat format, ZoneId zone) {
        this(format, zone, UnaryOperator.identity());
    }

    /** A formatter that writes stack traces through {@code traceMask}. */
    LineFormatter(LineFormat format, ZoneId zone, UnaryOperator<String> traceMask) {
        this.format = Objects.requireNonNull(format, "format");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.traceMask = Objects.requireNonNull(traceMask, "traceMask");
    }

    @Override
    public String format(LogRecord record) {
        String source = record.getLoggerName() == null ? WrapperLog.WRAPPER_SOURCE : record.getLoggerName();
        String thread = record instanceof WrapperRecord wrapper ? wrapper.thread() : "";
        StringBuilder line = new StringBuilder();
        boolean first = true;
        for (LineFormat.Field field : format.fields()) {
            // By position, not by what is written so far: an empty first field still has its separator.
            if (!first) {
                line.append(SEPARATOR);
            }
            first = false;
            switch (field) {
                case LEVEL -> pad(line, record.getLevel().getName(), LEVEL_WIDTH);
                case PREFIX -> pad(line, source, SOURCE_WIDTH);
                case THREAD -> line.append(thread);
                case TIME -> line.append(TIME.format(record.getInstant().atZone(zone)));
                case MILLIS -> line.append(MILLIS.format(record.getInstant().atZone(zone)));
                case MESSAGE -> line.append(lineBreaks(record.getMessage()));
            }
        }
        line.append(format.lineSeparator());
        if (record.getThrown() != null) {
            StringWriter trace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(trace));
            line.append(lineBreaks(traceMask.apply(trace.toString())));
        }
        return line.toString();
    }

    private String lineBreaks(String text) {
        String separator = format.lineSeparator();
        return separator.equals(System.lineSeparator()) ? text : text.replace(System.lineSeparator(), separator);
    }

    private static void pad(StringBuilder line, String text, int width) {
        line.append(text);
        for (int i = text.length(); i < width; i++) {
            line.append(' ');
        }
    }
}
