package io.jsl.nyajsw.log;

import java.io.PrintStream;
import java.util.Objects;
import java.util.logging.ErrorManager;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Writes log records to a console stream, one flushed line per record. Unlike the JDK's own
 * console handler it writes to the stream it is given (standard output by default, as YAJSW does)
 * and never closes it: the stream belongs to the process. A record that cannot be formatted is
 * reported to the handler's error manager instead of failing the code that logged it.
 */
final class ConsoleSink extends Handler {

    private final PrintStream out;

    ConsoleSink(PrintStream out, Formatter formatter, Level threshold) {
        this.out = Objects.requireNonNull(out, "out");
        setFormatter(Objects.requireNonNull(formatter, "formatter"));
        setLevel(Objects.requireNonNull(threshold, "threshold"));
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }
        String line;
        try {
            line = getFormatter().format(record);
        } catch (RuntimeException e) {
            reportError(null, e, ErrorManager.FORMAT_FAILURE);
            return;
        }
        out.print(line);
        out.flush();
    }

    @Override
    public synchronized void flush() {
        out.flush();
    }

    /** Flushes only; the stream stays open for the rest of the process. */
    @Override
    public void close() {
        flush();
    }
}
