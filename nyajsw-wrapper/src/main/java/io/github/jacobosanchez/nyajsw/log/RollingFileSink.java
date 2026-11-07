package io.github.jacobosanchez.nyajsw.log;

import io.github.jacobosanchez.nyajsw.config.LogFileNames;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.logging.ErrorManager;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Writes log records to the wrapper's log file ({@code wrapper.logfile}) and rolls it by size
 * ({@code wrapper.logfile.maxsize}), keeping at most {@code wrapper.logfile.maxfiles} rolled copies
 * named as {@link LogFileNames} says (plan §4.8). Every record is flushed when written, so that
 * nothing is lost when the JVM halts (D21) and a reader sees each line at once.
 *
 * <p>A record that would take the file past the limit rolls it first: the copies below the first
 * free number move up one (when none is free, the oldest copy the limit allows is deleted to make
 * room), and the file becomes copy 1. On Windows a move or a delete fails while another process (a
 * virus scanner, a backup, a tail tool) holds the file; each is retried a few times, and when one
 * still fails the sink goes on appending to the file, reports the failure to its error manager and
 * tries again a minute later. Since a retry only fills the gap the failed attempt left, a file that
 * stays locked costs no further old copy.
 */
final class RollingFileSink extends Handler implements AutoCloseable {

    /** A file operation that Windows can make fail at random. */
    @FunctionalInterface
    interface FileAction {
        void run() throws IOException;
    }

    /** Moves a file, replacing the target. */
    @FunctionalInterface
    interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    /** Deletes a file if it exists. */
    @FunctionalInterface
    interface Deleter {
        void delete(Path file) throws IOException;
    }

    /** Waits between attempts; tests do not wait. */
    @FunctionalInterface
    interface Pause {
        void pause(Duration duration) throws InterruptedException;
    }

    /**
     * What the sink does to the world besides writing, replaced in tests: moving and deleting
     * files, waiting between attempts, and a monotonic ticker in nanoseconds for the wait before a
     * roll is tried again, so that setting the system clock back cannot hold rolling up.
     */
    record Seams(Mover mover, Deleter deleter, Pause pause, LongSupplier nanoTicker) {

        static final Seams REAL = new Seams((from, to) -> Files.move(from, to, StandardCopyOption.REPLACE_EXISTING),
                Files::deleteIfExists, duration -> Thread.sleep(duration.toMillis()), System::nanoTime);

        Seams {
            Objects.requireNonNull(mover, "mover");
            Objects.requireNonNull(deleter, "deleter");
            Objects.requireNonNull(pause, "pause");
            Objects.requireNonNull(nanoTicker, "nanoTicker");
        }
    }

    /** The file settings that the configuration gives. */
    record Settings(Path file, long maxSize, int maxFiles, Charset charset) {

        Settings {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(charset, "charset");
            if (maxSize < 0 || maxFiles < 0) {
                throw new IllegalArgumentException("negative limits: " + maxSize + ", " + maxFiles);
            }
        }
    }

    static final int ATTEMPTS = 5;
    static final Duration PAUSE = Duration.ofMillis(100);
    static final Duration RETRY_ROLL_AFTER = Duration.ofMinutes(1);

    private final Settings settings;
    private final LogFileNames names;
    private final Seams seams;
    private final UnaryOperator<String> mask;
    private final LocalDate date;
    private final Path file;
    private OutputStream out;
    private long size;
    private boolean closed;
    /** While a roll is blocked, the ticker reading from which it is tried again. */
    private long noRollBefore;
    private boolean rollBlocked;

    private RollingFileSink(Settings settings, Formatter formatter, Level threshold, Clock clock,
            UnaryOperator<String> mask, Seams seams) throws IOException {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.mask = Objects.requireNonNull(mask, "mask");
        this.seams = Objects.requireNonNull(seams, "seams");
        this.names = new LogFileNames(settings.file());
        setFormatter(Objects.requireNonNull(formatter, "formatter"));
        setLevel(Objects.requireNonNull(threshold, "threshold"));
        this.date = LocalDate.now(Objects.requireNonNull(clock, "clock"));
        this.file = names.current(date);
        Path directory = file.toAbsolutePath().getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        open();
    }

    /**
     * Opens (or creates, with its directories) the log file for appending; the date in a {@code
     * YYYYMMDD} name is today's in {@code clock}'s zone. What the sink reports to its error manager
     * goes through {@code mask}, since the file name may hold a secret copied in with
     * <code>${...}</code>.
     *
     * @throws IOException              when the file cannot be opened
     * @throws IllegalArgumentException when the file name breaks the rules of {@link LogFileNames}
     */
    static RollingFileSink open(Settings settings, Formatter formatter, Level threshold, Clock clock,
            UnaryOperator<String> mask) throws IOException {
        return open(settings, formatter, threshold, clock, mask, Seams.REAL);
    }

    static RollingFileSink open(Settings settings, Formatter formatter, Level threshold, Clock clock,
            UnaryOperator<String> mask, Seams seams) throws IOException {
        return new RollingFileSink(settings, formatter, threshold, clock, mask, seams);
    }

    /** The file written to. */
    Path file() {
        return file;
    }

    @Override
    public synchronized void publish(LogRecord record) {
        // A thread that took the logger's handlers before close() may still arrive here.
        if (closed || !isLoggable(record)) {
            return;
        }
        byte[] bytes;
        try {
            bytes = getFormatter().format(record).getBytes(settings.charset());
        } catch (RuntimeException e) {
            reportError(null, e, ErrorManager.FORMAT_FAILURE);
            return;
        }
        try {
            if (out == null) {
                open(); // an earlier reopen failed
            }
            if (isFull(bytes.length)) {
                roll();
            }
            out.write(bytes);
            out.flush();
            size += bytes.length;
        } catch (IOException e) {
            report("cannot write the log file " + file, e, ErrorManager.WRITE_FAILURE);
        }
    }

    private boolean isFull(int adding) {
        if (settings.maxSize() == 0 || size == 0 || size + adding <= settings.maxSize()) {
            return false;
        }
        return !rollBlocked || seams.nanoTicker().getAsLong() - noRollBefore >= 0;
    }

    /** Rolls the file; afterwards the current file is open again, new and empty when the roll worked. */
    private void roll() throws IOException {
        closeStream();
        try {
            if (Files.exists(file)) { // someone may have removed it while it was open
                freeFirstCopy();
                retrying("move " + file, () -> seams.mover().move(file, names.rolled(date, 1)));
            }
            rollBlocked = false;
        } catch (IOException e) {
            rollBlocked = true;
            noRollBefore = seams.nanoTicker().getAsLong() + RETRY_ROLL_AFTER.toNanos();
            report("cannot roll the log file " + file + "; writing on in it, trying again in "
                    + RETRY_ROLL_AFTER.toSeconds() + " s", e, ErrorManager.GENERIC_FAILURE);
        }
        open();
    }

    /**
     * Frees copy 1: the copies below the first free number move up one. When every number up to
     * {@code maxfiles} is taken, the last one is deleted first; without a limit there is always a
     * free number.
     */
    private void freeFirstCopy() throws IOException {
        int limit = settings.maxFiles();
        int free = 1;
        while (Files.exists(names.rolled(date, free)) && (limit == 0 || free < limit)) {
            free++;
        }
        Path last = names.rolled(date, free);
        if (Files.exists(last)) { // every number up to the limit is taken
            retrying("delete " + last, () -> seams.deleter().delete(last));
        }
        for (int number = free - 1; number >= 1; number--) {
            Path from = names.rolled(date, number);
            Path to = names.rolled(date, number + 1);
            retrying("move " + from, () -> seams.mover().move(from, to));
        }
    }

    private void retrying(String what, FileAction action) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                action.run();
                return;
            } catch (IOException e) {
                if (attempt == ATTEMPTS) {
                    throw e;
                }
            }
            try {
                seams.pause().pause(PAUSE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while trying to " + what, e);
            }
        }
    }

    private void open() throws IOException {
        out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        size = Files.size(file);
    }

    @Override
    public synchronized void flush() {
        if (out != null) {
            try {
                out.flush();
            } catch (IOException e) {
                report("cannot flush the log file " + file, e, ErrorManager.FLUSH_FAILURE);
            }
        }
    }

    /** Closes the file for good: records that still arrive are dropped. */
    @Override
    public synchronized void close() {
        closed = true;
        closeStream();
    }

    private void closeStream() {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException e) {
            report("cannot close the log file " + file, e, ErrorManager.CLOSE_FAILURE);
        } finally {
            out = null;
        }
    }

    /**
     * Reports a failure masked, without the exception itself: its message and stack trace would
     * repeat the file name unmasked.
     */
    private void report(String message, IOException error, int code) {
        reportError(mask.apply(message + ": " + error), null, code);
    }
}
