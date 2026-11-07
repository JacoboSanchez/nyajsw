package io.github.jacobosanchez.nyajsw.log;

import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * A log record that also names the thread that logged it, for the thread field of a line ({@link
 * LineFormatter}): a {@link LogRecord} keeps only the thread's id, and the name cannot be looked up
 * once the thread may have ended.
 */
final class WrapperRecord extends LogRecord {

    private static final long serialVersionUID = 1L;

    private final String thread;

    WrapperRecord(Level level, String message) {
        super(level, message);
        this.thread = Thread.currentThread().getName();
    }

    String thread() {
        return thread;
    }
}
