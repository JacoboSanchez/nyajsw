package io.github.jacobosanchez.nyajsw.log;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.DebugDetail;
import io.github.jacobosanchez.nyajsw.config.KeyDefault;
import io.github.jacobosanchez.nyajsw.config.LineFormat;
import io.github.jacobosanchez.nyajsw.config.LogConfig;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.config.SecretMasker;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The wrapper's only logging entry point (AGENTS.md §4.4): every wrapper message goes through an
 * instance of this facade, which is passed to the code that logs rather than looked up statically.
 * It sits on {@code java.util.logging} (D6) with the YAJSW level names of {@link LogLevel}, and
 * writes lines in a {@link LineFormat} to the console and the rolling log file.
 *
 * <p>Each sink has its own threshold ({@code wrapper.console.loglevel}, {@code
 * wrapper.logfile.loglevel}); a message is built and passed on when at least one sink would write
 * it. An instance is immutable: the command-line entry point builds an early console log first and a
 * new, configured one once the configuration is read, instead of reconfiguring a log in place.
 *
 * <p>Debug messages also carry a {@link DebugDetail}; a log writes those up to its own detail
 * ({@code wrapper.debug.level}), shared with its views. Captured application output is logged at
 * {@code INFO}, so the detail never hides it; output logged at {@code DEBUG} would count as {@link
 * DebugDetail#ALL}.
 *
 * <p>Every line names its source: {@code wrapper} for the wrapper's own messages, and the views
 * returned by {@link #forSource(String)} (such as {@code jvm 1}) for captured application output.
 *
 * <p>Messages are written as given. Configuration values reach them only through the configuration
 * layer, which masks secrets ({@code ConfigDump}, the masking form of the command line); the only
 * value this class shows itself, the log file's name in its own errors, it masks with the
 * configuration's {@link SecretMasker}.
 */
public final class WrapperLog implements AutoCloseable {

    /** The source of the wrapper's own messages. */
    public static final String WRAPPER_SOURCE = "wrapper";

    /** How the names of {@link #bootstrapFile} logs begin. */
    static final String BOOTSTRAP_PREFIX = "nyajsw-bootstrap-";

    private final Logger logger;
    private final String source;
    private final DebugDetail debugDetail;
    /** Stamps the records, so that the time on a line and the date of the file come from one clock. */
    private final Clock clock;

    private WrapperLog(Logger logger, String source, DebugDetail debugDetail, Clock clock) {
        this.logger = logger;
        this.source = source;
        this.debugDetail = debugDetail;
        this.clock = clock;
    }

    private static WrapperLog of(Clock clock, DebugDetail debugDetail, Handler... sinks) {
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
        return new WrapperLog(logger, WRAPPER_SOURCE, debugDetail, clock);
    }

    /** A log that writes messages at {@code threshold} and above to standard output, all debug detail included. */
    public static WrapperLog console(LogLevel threshold) {
        return console(System.out, threshold);
    }

    /**
     * A log that writes messages at {@code threshold} and above to {@code out}, which it never closes,
     * all debug messages included.
     */
    public static WrapperLog console(PrintStream out, LogLevel threshold) {
        return console(out, threshold, DebugDetail.ALL);
    }

    /**
     * A log that writes messages at {@code threshold} and above to {@code out}, which it never closes;
     * of the debug messages, only those of at most {@code debugDetail} ({@code wrapper.debug.level}).
     */
    static WrapperLog console(PrintStream out, LogLevel threshold, DebugDetail debugDetail) {
        Objects.requireNonNull(debugDetail, "debugDetail");
        Clock clock = Clock.systemDefaultZone();
        return of(clock, debugDetail, consoleSink(out, LineFormat.CONSOLE_DEFAULT, clock.getZone(), threshold));
    }

    /**
     * The log {@code config} describes, with times and dates in the system's zone, for console mode:
     * what goes wrong with the log file goes to the console; see {@link #open(LogConfig, PrintStream,
     * SecretMasker, Consumer)}.
     */
    public static WrapperLog open(LogConfig config, PrintStream console, SecretMasker secrets) {
        return open(config, console, secrets, null, Clock.systemDefaultZone(), System.err);
    }

    /**
     * The log {@code config} describes, with what goes wrong with the log file (it cannot be opened,
     * a roll fails, the disk is full) sent to {@code fileProblems}, masked, instead of the console:
     * for service mode (P6), which has no console and passes the {@link #bootstrap} log.
     */
    public static WrapperLog open(LogConfig config, PrintStream console, SecretMasker secrets,
            Consumer<String> fileProblems) {
        Objects.requireNonNull(fileProblems, "fileProblems");
        return open(config, console, secrets, fileProblems, Clock.systemDefaultZone(), System.err);
    }

    /**
     * The log {@code config} describes:
     *
     * <ul>
     *   <li>the console ({@code console}, never closed) in {@code wrapper.console.format} from {@code
     *       wrapper.console.loglevel} up, or no console output when the format is blank;
     *   <li>the log file ({@code wrapper.logfile}, rolled as {@link RollingFileSink} says) in {@code
     *       wrapper.logfile.format} from {@code wrapper.logfile.loglevel} up, or none when either is
     *       blank or the level is {@code NONE}; a file that cannot be opened is reported, and the log
     *       goes on without it;
     *   <li>debug messages up to {@code wrapper.debug.level}.
     * </ul>
     *
     * What goes wrong with the file goes to {@code fileProblems}, or, when that is {@code null}, to
     * the console as an {@code ERROR} line where the console shows those, and to {@code stderr}
     * otherwise. Times on the lines are in {@code clock}'s zone, and so is the date of a {@code
     * YYYYMMDD} file name. File names in messages are masked with {@code secrets}.
     */
    static WrapperLog open(LogConfig config, PrintStream console, SecretMasker secrets,
            Consumer<String> fileProblems, Clock clock, PrintStream stderr) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(console, "console");
        Objects.requireNonNull(secrets, "secrets");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(stderr, "stderr");
        ZoneId zone = clock.getZone();
        List<Handler> sinks = new ArrayList<>();
        Handler consoleSink = config.consoleFormat()
                .map(format -> consoleSink(console, format, zone, config.consoleThreshold())).orElse(null);
        if (consoleSink != null) {
            sinks.add(consoleSink);
        }
        Consumer<String> problems = fileProblems != null ? fileProblems : reporter(consoleSink, clock, stderr);
        if (config.logfile().isPresent() && config.logfileFormat().isPresent()
                && config.logfileThreshold() != LogLevel.NONE) {
            try {
                RollingFileSink.Settings settings = new RollingFileSink.Settings(config.logfile().get(),
                        config.maxSizeBytes().orElse(0L), config.maxFiles().orElse(0), config.encoding(),
                        config.rollMode(), config.maxDays());
                sinks.add(RollingFileSink.open(settings, new LineFormatter(config.logfileFormat().get(), zone),
                        WrapperLevel.threshold(config.logfileThreshold()), clock, secrets::maskText,
                        new ForwardingErrorManager(problems)));
            } catch (IOException | RuntimeException e) {
                problems.accept(secrets.maskText("cannot open the log file " + config.logfile().get() + ": " + e)
                        + "; the log goes on without it");
            }
        }
        return of(clock, config.debugDetail(), sinks.toArray(Handler[]::new));
    }

    /**
     * Where the log file's own failures go in console mode: straight to the console sink, past the
     * logger, which would hand them to the failing file again, when the console shows {@code ERROR}
     * lines; to {@code stderr} when it does not or there is no console.
     */
    static Consumer<String> reporter(Handler console, Clock clock, PrintStream stderr) {
        return message -> {
            LogRecord record = record(LogLevel.ERROR, WRAPPER_SOURCE, message, clock);
            if (console != null && console.isLoggable(record)) {
                console.publish(record);
            } else {
                stderr.println("nyajsw: " + message);
            }
        };
    }

    /**
     * A log that writes only to {@code file}, for the wrapper before its configuration is read and
     * for the problems of the configured log file, where there is no console (service mode, P6):
     * YAJSW's default file format and level (the registry's defaults of {@code
     * wrapper.logfile.format} and {@code wrapper.logfile.loglevel}), UTF-8. It never rolls: it is
     * written rarely, and its usual place, the temporary directory, is writable by every user, where
     * moving and deleting files by a predictable name is best avoided. When the file cannot be
     * opened either, the reason goes to standard error and the log writes nowhere; it never fails
     * its caller.
     */
    public static WrapperLog bootstrap(Path file) {
        return bootstrap(file, Clock.systemDefaultZone(), System.err);
    }

    static WrapperLog bootstrap(Path file, Clock clock, PrintStream stderr) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(clock, "clock");
        ErrorManager errors = new ForwardingErrorManager(reporter(null, clock, stderr));
        LogLevel level = LogLevel.valueOf(
                ((KeyDefault.Constant) ConfigKeys.LOGFILE_LOGLEVEL.defaultValue()).value());
        try {
            RollingFileSink sink = RollingFileSink.open(
                    new RollingFileSink.Settings(file, 0, 0, StandardCharsets.UTF_8),
                    new LineFormatter(LineFormat.LOGFILE_DEFAULT, clock.getZone()), WrapperLevel.threshold(level),
                    clock, UnaryOperator.identity(), errors);
            return of(clock, DebugDetail.ALL, sink);
        } catch (IOException | RuntimeException e) {
            errors.error("cannot open the bootstrap log " + file + ": " + e, null, ErrorManager.OPEN_FAILURE);
            return of(clock, DebugDetail.ALL);
        }
    }

    /**
     * The bootstrap log's usual place for {@code name} (a service name), {@code
     * nyajsw-bootstrap-<name>.log} in the JVM's temporary directory ({@code %TEMP%}; for {@code
     * LocalSystem}, the system's): one file per service, so that services do not share one.
     * Characters other than letters, digits, {@code .}, {@code _} and {@code -} become {@code _}.
     */
    public static Path bootstrapFile(String name) {
        Objects.requireNonNull(name, "name");
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return Path.of(System.getProperty("java.io.tmpdir"), BOOTSTRAP_PREFIX + safe + ".log");
    }

    private static LogRecord record(LogLevel level, String source, String message, Clock clock) {
        LogRecord record = new WrapperRecord(WrapperLevel.of(level), message);
        record.setLoggerName(source);
        record.setInstant(clock.instant());
        return record;
    }

    private static Handler consoleSink(PrintStream out, LineFormat format, ZoneId zone, LogLevel threshold) {
        return new ConsoleSink(out, new LineFormatter(format, zone), WrapperLevel.threshold(threshold));
    }

    /**
     * Tells the sinks that the application is being started for the {@code invocation}th time, 1
     * for the first; the log file rolls then under {@code wrapper.logfile.rollmode=JVM}. Shared with
     * the {@link #forSource} views.
     */
    public void applicationStarting(int invocation) {
        for (Handler handler : logger.getHandlers()) {
            if (handler instanceof RollingFileSink file) {
                file.applicationStarting(invocation);
            }
        }
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
        return new WrapperLog(logger, source, debugDetail, clock);
    }

    /** The source this log's lines name, {@code wrapper} unless this is a {@link #forSource} view. */
    public String source() {
        return source;
    }

    /**
     * Whether some sink would write a message of {@code level}; use it to skip building expensive
     * messages, or log through a {@link Supplier}. A {@code DEBUG} message logged without a detail
     * has the most, {@link DebugDetail#ALL}.
     *
     * @throws IllegalArgumentException for {@link LogLevel#NONE}
     */
    public boolean isEnabled(LogLevel level) {
        return level == LogLevel.DEBUG ? isEnabled(DebugDetail.ALL) : passes(level);
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
        if (isEnabled(level)) {
            write(level, message, error);
        }
    }

    private boolean passes(LogLevel level) {
        return logger.isLoggable(WrapperLevel.of(level));
    }

    private void write(LogLevel level, String message, Throwable error) {
        LogRecord record = record(level, source, message, clock);
        record.setThrown(error);
        logger.log(record);
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

    /** Logs a debug message of the most detail, {@link DebugDetail#ALL}. */
    public void debug(String message) {
        debug(DebugDetail.ALL, message);
    }

    /** Logs a supplied debug message of the most detail, {@link DebugDetail#ALL}. */
    public void debug(Supplier<String> message) {
        debug(DebugDetail.ALL, message);
    }

    /**
     * Logs a debug message of {@code detail}, written when {@code DEBUG} is enabled and the detail
     * is within this log's {@code wrapper.debug.level}.
     *
     * @throws IllegalArgumentException for {@link DebugDetail#NONE}, which is only a threshold
     */
    public void debug(DebugDetail detail, String message) {
        Objects.requireNonNull(message, "message");
        debug(detail, () -> message);
    }

    /**
     * Logs a supplied debug message of {@code detail}; the supplier only runs when it is written.
     *
     * @throws IllegalArgumentException for {@link DebugDetail#NONE}, which is only a threshold
     */
    public void debug(DebugDetail detail, Supplier<String> message) {
        Objects.requireNonNull(message, "message");
        if (isEnabled(detail)) {
            write(LogLevel.DEBUG, Objects.requireNonNull(message.get(), "message"), null);
        }
    }

    /**
     * Whether a debug message of {@code detail} would be written.
     *
     * @throws IllegalArgumentException for {@link DebugDetail#NONE}, which is only a threshold
     */
    public boolean isEnabled(DebugDetail detail) {
        if (Objects.requireNonNull(detail, "detail") == DebugDetail.NONE) {
            throw new IllegalArgumentException("NONE is a threshold, not the detail of a message");
        }
        return detail.compareTo(debugDetail) <= 0 && passes(LogLevel.DEBUG);
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
