package io.github.jacobosanchez.nyajsw.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * The fixed line layout used until the configurable formats of {@code wrapper.logfile.format} and
 * {@code wrapper.console.format} arrive (P4.2). It is YAJSW's default console format, {@code PM}:
 * the source ({@code wrapper}, or {@code jvm 1} for captured application output), padded so the
 * messages line up, and the message; then the stack trace of an attached error. Lines end with the
 * platform line separator.
 */
final class LineFormatter extends Formatter {

    /** Wide enough for {@code wrapper} and {@code jvm} with a two-digit invocation number. */
    private static final int SOURCE_WIDTH = 8;

    @Override
    public String format(LogRecord record) {
        String source = record.getLoggerName() == null ? WrapperLog.WRAPPER_SOURCE : record.getLoggerName();
        StringBuilder line = new StringBuilder(source);
        for (int i = source.length(); i < SOURCE_WIDTH; i++) {
            line.append(' ');
        }
        line.append(" | ").append(record.getMessage()).append(System.lineSeparator());
        if (record.getThrown() != null) {
            StringWriter trace = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(trace));
            line.append(trace);
        }
        return line.toString();
    }
}
