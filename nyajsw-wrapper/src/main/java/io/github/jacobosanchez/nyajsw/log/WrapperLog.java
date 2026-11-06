package io.github.jacobosanchez.nyajsw.log;

import io.github.jacobosanchez.nyajsw.config.LogLevel;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The wrapper's only logging entry point (AGENTS.md §4.4): every wrapper message goes through an
 * instance of this facade, which is passed to the code that logs rather than looked up statically.
 * It sits on {@code java.util.logging} (D6) with the YAJSW level names of {@link LogLevel}; this
 * phase has a console sink, the rolling log file follows in P4.
 *
 * <p>Each sink has its own threshold ({@code wrapper.console.loglevel}, later {@code
 * wrapper.logfile.loglevel}); a message is built and passed on when at least one sink would write
 * it. An instance is immutable: the command-line entry point builds a bootstrap log first and a new,
 * configured one once the configuration is read, instead of reconfiguring a log in place.
 *
 * <p>Every line names its source: {@code wrapper} for the wrapper's own messages, and the views
 * returned by {@link #forSource(String)} (such as {@code jvm 1}) for captured application output.
 *
 * <p>Messages are written as given. Configuration values reach them only through the configuration
 * layer, which masks secrets ({@code ConfigDump}, the masking form of the command line), so nothing
 * here needs to know about passwords.
 */
public final class WrapperLog implements AutoCloseable {

    /** The source of the wrapper's own messages. */
    public static final String WRAPPER_SOURCE = "wrapper";

    private final Logger logger;
    private final String source;

    private WrapperLog(Logger logger, String source) {
        this.logger = logger;
        this.source = source;
    }

    private static WrapperLog of(Handler... sinks) {
        // Anonymous: not registered with the LogManager, so neither logging.properties, root handlers
        // nor LogManager.reset() (also run by the JDK's shutdown hook) reach it.
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        // The logger passes what the most verbose sink wants; each sink filters by its own level.
        logger.setLevel(Arrays.stream(sinks).map(Handler::getLevel)
                .min(Comparator.comparingInt(Level::intValue)).orElse(Level.OFF));
        for (Handler sink : sinks) {
            logger.addHandler(sink);
        }
        return new WrapperLog(logger, WRAPPER_SOURCE);
    }

    /** A log that writes messages at {@code threshold} and above to standard output. */
    public static WrapperLog console(LogLevel threshold) {
        return console(System.out, threshold);
    }

    /** A log that writes messages at {@code threshold} and above to {@code out}, which it never closes. */
    public static WrapperLog console(PrintStream out, LogLevel threshold) {
        return of(new ConsoleSink(out, new LineFormatter(), WrapperLevel.threshold(threshold)));
    }

    /**
     * A view of this log whose lines name {@code source} instead of {@code wrapper}, for output
     * captured from the application ({@code jvm 1}); it shares the sinks and their thresholds.
     */
    public WrapperLog forSource(String source) {
        Objects.requireNonNull(source, "source");
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        return new WrapperLog(logger, source);
    }

    /** The source this log's lines name, {@code wrapper} unless this is a {@link #forSource} view. */
    public String source() {
        return source;
    }

    /**
     * Whether some sink would write a message of {@code level}; use it to skip building expensive
     * messages, or log through a {@link Supplier}.
     *
     * @throws IllegalArgumentException for {@link LogLevel#NONE}
     */
    public boolean isEnabled(LogLevel level) {
        return logger.isLoggable(WrapperLevel.of(level));
    }

    /**
     * Logs {@code message} at {@code level}.
     *
     * @throws IllegalArgumentException for {@link LogLevel#NONE}, which is only a threshold
     */
    public void log(LogLevel level, String message) {
        log(level, message, null);
    }

    /**
     * Logs {@code message} at {@code level} with the stack trace of {@code error}, if not {@code null}.
     *
     * @throws IllegalArgumentException for {@link LogLevel#NONE}, which is only a threshold
     */
    public void log(LogLevel level, String message, Throwable error) {
        Objects.requireNonNull(message, "message");
        Level julLevel = WrapperLevel.of(level);
        if (logger.isLoggable(julLevel)) {
            LogRecord record = new LogRecord(julLevel, message);
            record.setLoggerName(source);
            record.setThrown(error);
            logger.log(record);
        }
    }

    /** Logs the supplied message at {@code level}; the supplier only runs when the level is enabled. */
    public void log(LogLevel level, Supplier<String> message) {
        Objects.requireNonNull(message, "message");
        if (isEnabled(level)) {
            log(level, message.get());
        }
    }

    public void fatal(String message) {
        log(LogLevel.FATAL, message);
    }

    public void fatal(String message, Throwable error) {
        log(LogLevel.FATAL, message, error);
    }

    public void error(String message) {
        log(LogLevel.ERROR, message);
    }

    public void error(String message, Throwable error) {
        log(LogLevel.ERROR, message, error);
    }

    public void status(String message) {
        log(LogLevel.STATUS, message);
    }

    public void info(String message) {
        log(LogLevel.INFO, message);
    }

    public void debug(String message) {
        log(LogLevel.DEBUG, message);
    }

    public void debug(Supplier<String> message) {
        log(LogLevel.DEBUG, message);
    }

    /**
     * Flushes and detaches the sinks, for this log and every {@link #forSource} view of it; the
     * console stream itself stays open.
     */
    @Override
    public void close() {
        for (Handler handler : logger.getHandlers()) {
            logger.removeHandler(handler);
            handler.close();
        }
    }
}
