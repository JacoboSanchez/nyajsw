package io.github.jacobosanchez.nyajsw.cli;

import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A service's bootstrap log ({@link WrapperLog#bootstrap}), opened only when there is something to
 * write: the errors before the configured log is open and the problems of the log file itself. A
 * service that starts cleanly touches nothing in the temporary directory.
 */
final class BootstrapLog implements Consumer<String>, AutoCloseable {

    private final Path file;
    private WrapperLog log;

    BootstrapLog(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Writes {@code message} as an error. */
    @Override
    public synchronized void accept(String message) {
        open().error(message);
    }

    /** Writes {@code message} as fatal: the service cannot go on. */
    synchronized void fatal(String message) {
        open().fatal(message);
    }

    private WrapperLog open() {
        if (log == null) {
            log = WrapperLog.bootstrap(file);
        }
        return log;
    }

    @Override
    public synchronized void close() {
        if (log != null) {
            log.close();
        }
    }
}
