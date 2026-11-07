package io.github.jacobosanchez.nyajsw.cli;

import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A service's bootstrap log ({@link WrapperLog#bootstrap}), opened only when there is something to
 * write: the errors before the configured log is open and the problems of the log file itself. A
 * service that starts cleanly writes nothing. It goes to {@code file}, beside the configuration,
 * and to {@code fallback}, the account's temporary directory, when the account may not create
 * {@code file}, so that a restricted account still leaves a trace (plan P6.3).
 */
final class BootstrapLog implements Consumer<String>, AutoCloseable {

    private final Path file;
    private final Path fallback;
    private WrapperLog log;

    BootstrapLog(Path file, Path fallback) {
        this.file = Objects.requireNonNull(file, "file");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
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
            log = WrapperLog.bootstrap(writable(file) ? file : fallback);
        }
        return log;
    }

    /** Whether {@code candidate} can be created or appended to; the bootstrap log itself writes nowhere when not. */
    private static boolean writable(Path candidate) {
        try (OutputStream probe = Files.newOutputStream(candidate, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (log != null) {
            log.close();
        }
    }
}
