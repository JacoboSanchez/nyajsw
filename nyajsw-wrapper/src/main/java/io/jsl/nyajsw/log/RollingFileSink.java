package io.jsl.nyajsw.log;

import io.jsl.nyajsw.config.LogFileNames;
import io.jsl.nyajsw.config.RollMode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * nothing is lost when the JVM halts (D21) and a reader sees each line at once. One encoder serves
 * each file from the moment it is opened, so that an encoding with a byte-order mark ({@code UTF-16})
 * writes the mark once, at the start of the file, not before every record.
 *
 * <p>A record that would take the file past the limit rolls it first: the copies below the first
 * free number move up one (when none is free, the oldest copy the limit allows is deleted to make
 * room), and the file becomes copy 1. On Windows a move or a delete fails while another process (a
 * virus scanner, a backup, a tail tool) holds the file; each is retried a few times, and when one
 * still fails the sink goes on appending to the file, reports the failure to its error manager and
 * tries again a minute later. Since a retry only fills the gap the failed attempt left, a file that
 * stays locked costs no further old copy.
 *
 * <p>The roll modes ({@code wrapper.logfile.rollmode}) add rolls: {@code JVM} and {@code WRAPPER}
 * roll a file that is not empty when the sink opens it, {@code JVM} also before every restarted
 * application ({@link #applicationStarting}); {@code DATE} writes each record to the file of its
 * day, opened with the day's first record (so a day without output has no file), and deletes the
 * files of days past {@code wrapper.logfile.maxdays}. Rolling by size goes on within each file.
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

    /**
     * The file settings that the configuration gives.
     *
     * @param mode    {@code wrapper.logfile.rollmode}; empty to roll by size only
     * @param maxDays {@code wrapper.logfile.maxdays}, for the {@code DATE} mode; negative keeps every day
     */
    record Settings(Path file, long maxSize, int maxFiles, Charset charset, Optional<RollMode> mode, int maxDays) {

        Settings {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(charset, "charset");
            Objects.requireNonNull(mode, "mode");
            if (maxSize < 0 || maxFiles < 0) {
                throw new IllegalArgumentException("negative limits: " + maxSize + ", " + maxFiles);
            }
        }

        /** Rolling by size only, with every day kept. */
        Settings(Path file, long maxSize, int maxFiles, Charset charset) {
            this(file, maxSize, maxFiles, charset, Optional.empty(), -1);
        }

        boolean is(RollMode rollMode) {
            return mode.equals(Optional.of(rollMode));
        }
    }

    static final int ATTEMPTS = 5;
    static final Duration PAUSE = Duration.ofMillis(100);
    static final Duration RETRY_ROLL_AFTER = Duration.ofMinutes(1);

    private final Settings settings;
    private final LogFileNames names;
    private final Seams seams;
    private final UnaryOperator<String> mask;
    private final ZoneId zone;
    /** The day of the file; under {@code DATE}, of the last record written. */
    private LocalDate date;
    private Path file;
    private OutputStream out;
    /** The encoder of the open file, which keeps its state from one record to the next. */
    private CharsetEncoder encoder;
    private long size;
    private boolean closed;
    /** While a roll is blocked, the ticker reading from which it is tried again. */
    private long noRollBefore;
    private boolean rollBlocked;

    private RollingFileSink(Settings settings, Formatter formatter, Level threshold, Clock clock,
            UnaryOperator<String> mask, ErrorManager errors, Seams seams) throws IOException {
        setErrorManager(Objects.requireNonNull(errors, "errors")); // before the start roll can fail
        this.settings = Objects.requireNonNull(settings, "settings");
        this.mask = Objects.requireNonNull(mask, "mask");
        this.seams = Objects.requireNonNull(seams, "seams");
        this.names = new LogFileNames(settings.file());
        setFormatter(Objects.requireNonNull(formatter, "formatter"));
        setLevel(Objects.requireNonNull(threshold, "threshold"));
        this.zone = Objects.requireNonNull(clock, "clock").getZone();
        this.date = LocalDate.now(clock);
        this.file = names.current(date);
        Path directory = file.toAbsolutePath().getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        open();
        if ((settings.is(RollMode.JVM) || settings.is(RollMode.WRAPPER)) && size > 0) {
            roll(); // both modes start every wrapper run in a new file
        }
        if (settings.is(RollMode.DATE)) {
            deleteOldDays();
        }
    }

    /**
     * Opens (or creates, with its directories) the log file for appending; the date in a {@code
     * YYYYMMDD} name is today's in {@code clock}'s zone. What the sink reports to {@code errors} goes
     * through {@code mask}, since the file name may hold a secret copied in with <code>${...}</code>;
     * {@code errors} is in place before the file is opened, so it also hears of a failed start roll.
     *
     * @throws IOException              when the file cannot be opened
     * @throws IllegalArgumentException when the file name breaks the rules of {@link LogFileNames}
     */
    static RollingFileSink open(Settings settings, Formatter formatter, Level threshold, Clock clock,
            UnaryOperator<String> mask, ErrorManager errors) throws IOException {
        return open(settings, formatter, threshold, clock, mask, errors, Seams.REAL);
    }

    static RollingFileSink open(Settings settings, Formatter formatter, Level threshold, Clock clock,
            UnaryOperator<String> mask, ErrorManager errors, Seams seams) throws IOException {
        return new RollingFileSink(settings, formatter, threshold, clock, mask, errors, seams);
    }

    /** The file written to. */
    synchronized Path file() {
        return file;
    }

    @Override
    public synchronized void publish(LogRecord record) {
        // A thread that took the logger's handlers before close() may still arrive here.
        if (closed || !isLoggable(record)) {
            return;
        }
        String text;
        try {
            text = getFormatter().format(record);
        } catch (RuntimeException e) {
            reportError(null, e, ErrorManager.FORMAT_FAILURE);
            return;
        }
        try {
            if (settings.is(RollMode.DATE)) {
                // Only forward: a record stamped just before midnight may arrive after one stamped just
                // after it, and a clock set back must not reopen the files of earlier days.
                LocalDate day = LocalDate.ofInstant(record.getInstant(), zone);
                if (day.isAfter(date)) {
                    startDay(day);
                }
            }
            if (out == null) {
                open(); // an earlier reopen failed
            }
            byte[] bytes = encode(text);
            if (isFull(bytes.length)) {
                roll();
                bytes = encode(text); // a new file starts with a new encoder, and its byte-order mark
            }
            out.write(bytes);
            out.flush();
            size += bytes.length;
        } catch (IOException e) {
            report("cannot write the log file " + file, e, ErrorManager.WRITE_FAILURE);
        }
    }

    /**
     * Tells the sink that the application is being started for the {@code invocation}th time: under
     * the {@code JVM} roll mode every restarted application begins a new file (the first one shares
     * the file the wrapper started).
     */
    synchronized void applicationStarting(int invocation) {
        if (closed || !settings.is(RollMode.JVM) || invocation <= 1 || size == 0) {
            return;
        }
        try {
            roll();
        } catch (IOException e) {
            report("cannot open the log file " + file + " again", e, ErrorManager.OPEN_FAILURE);
        }
    }

    /** Moves to the file of {@code day} (the {@code DATE} roll mode), opening it with the first record of the day. */
    private void startDay(LocalDate day) throws IOException {
        closeStream();
        date = day;
        file = names.current(day);
        rollBlocked = false;
        try {
            open();
        } finally {
            deleteOldDays(); // also when the day's file cannot be opened, so the clean-up is not lost
        }
    }

    /**
     * Deletes the files, current or rolled, of the days before the last {@code wrapper.logfile.maxdays}
     * ones; a file that cannot be deleted is reported and tried again with the next day.
     */
    private void deleteOldDays() {
        if (settings.maxDays() < 0) {
            return;
        }
        LocalDate oldestKept = date.minusDays(settings.maxDays());
        Path directory = file.toAbsolutePath().getParent();
        List<Path> old;
        try (var files = Files.list(directory)) {
            old = files.filter(Files::isRegularFile)
                    .filter(candidate -> names.dateOf(candidate).filter(day -> day.isBefore(oldestKept)).isPresent())
                    .toList();
        } catch (IOException e) {
            report("cannot list the log directory " + directory, e, ErrorManager.GENERIC_FAILURE);
            return;
        }
        for (Path stale : old) {
            try {
                retrying("delete " + stale, () -> seams.deleter().delete(stale));
            } catch (IOException e) {
                report("cannot delete the old log file " + stale, e, ErrorManager.GENERIC_FAILURE);
            }
        }
    }

    private boolean isFull(int adding) {
        if (settings.is(RollMode.NONE) || settings.maxSize() == 0 || size == 0
                || size + adding <= settings.maxSize()) {
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

    /** Opens the file with its own encoder; the stream is set last, so a failure leaves nothing half open. */
    private void open() throws IOException {
        OutputStream stream = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try {
            size = Files.size(file);
        } catch (IOException e) {
            try {
                stream.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        encoder = settings.charset().newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        if (size > 0) {
            encode(" "); // spends what an encoder writes first, such as a byte-order mark: the file has its own
        }
        out = stream;
    }

    /**
     * {@code text} in the file's encoding, by the file's encoder, which is never reset or told that
     * the input ends, so that it goes on from one record to the next as through one text. A record
     * ends with its line separator, so the encoder holds nothing back; should a record end with half
     * a surrogate pair, the encoding's replacement stands for it, as with {@link String#getBytes}.
     */
    private byte[] encode(String text) {
        CharBuffer chars = CharBuffer.wrap(text);
        ByteBuffer bytes = ByteBuffer.allocate((int) (text.length() * encoder.averageBytesPerChar()) + 16);
        for (CoderResult result = encoder.encode(chars, bytes, false); !result.isUnderflow();
                result = encoder.encode(chars, bytes, false)) {
            // Errors are replaced, so a result other than underflow is an overflow.
            bytes = grow(bytes, bytes.capacity());
        }
        if (chars.hasRemaining()) {
            byte[] replacement = encoder.replacement();
            if (bytes.remaining() < replacement.length) {
                bytes = grow(bytes, replacement.length);
            }
            bytes.put(replacement);
        }
        return Arrays.copyOf(bytes.array(), bytes.position());
    }

    private static ByteBuffer grow(ByteBuffer bytes, int more) {
        return ByteBuffer.allocate(bytes.capacity() + more).put(bytes.flip());
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
