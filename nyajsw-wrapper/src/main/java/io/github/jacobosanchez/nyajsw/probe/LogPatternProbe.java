package io.github.jacobosanchez.nyajsw.probe;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ProbeConfig;
import io.github.jacobosanchez.nyajsw.launch.OutputLines;
import io.github.jacobosanchez.nyajsw.launch.RunningApplication;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * The {@code log} probe: it passes when a line that came since the previous check contains a match
 * of {@code wrapper.readiness.log.pattern}; for the first check of an invocation, since its launch.
 * As readiness that is a line such as {@code Started in 4 s}; as liveness, a message the
 * application logs regularly, which must come at least once per {@code wrapper.liveness.interval}
 * for a check to pass. Readiness and liveness share the pattern (D25), so a readiness line and a
 * heartbeat need an alternation such as {@code Started|alive}.
 *
 * <p>The lines are the application's captured output ({@link RunningApplication#output()}, the
 * lines as the wrapper logs them, secrets masked); or, with {@code wrapper.readiness.log.file}, the
 * lines appended to that file since the launch ({@link #beforeLaunch} notes its size), read as
 * UTF-8 (a pattern in ASCII matches in any ASCII-based encoding). A file that becomes shorter is
 * read again from its start, as after a log rotation. At most {@link #MAX_READ} bytes are read per
 * check; a file that grew more is read from where that much is left. A line whose end has not come
 * yet is matched as far as it goes, and counts once.
 *
 * <p>As readiness, once a line has matched, later checks of the same invocation pass too: the
 * readiness line comes once, and another readiness probe in the list (such as {@code log,http})
 * may still be failing when it does, which fails the whole check. As liveness, every check needs a
 * new match.
 *
 * <p>Its state belongs to the invocation it last checked; a new invocation starts afresh. A check
 * that finds the previous one still running (a file share that hangs) fails at once rather than
 * waiting for it. For the same reason {@link #beforeLaunch} looks up the file's size on a thread of
 * its own and waits for it at most {@link #SIZE_WAIT}: it runs on the supervisor's thread, which a
 * hung share must not hold up (readiness and liveness each have their own probe, so a launch waits
 * at most twice that). A lookup that has not answered by then is still used once it does, and
 * checks fail until then; as the application has started meanwhile, its first lines may then not
 * count. A share that never answers leaves one blocked virtual thread per launch behind.
 */
public final class LogPatternProbe implements Probe {

    /** The type name the configuration uses. */
    public static final String TYPE = "log";

    /** The most bytes of a file read in one check. */
    static final int MAX_READ = 16 * 1024 * 1024;

    /** The longest line of a file kept while its end has not come yet; the rest of it is not matched. */
    static final int MAX_LINE = 64 * 1024;

    /** How long {@link #beforeLaunch} waits for the file's size before the launch goes ahead. */
    static final Duration SIZE_WAIT = Duration.ofSeconds(1);

    /** Looks up the size of a file; replaceable in tests by one that hangs. */
    @FunctionalInterface
    interface FileSize {
        long of(Path file) throws IOException;
    }

    private final Pattern pattern;
    private final Optional<Path> file;
    private final FileSize fileSize;
    private final Duration sizeWait;
    /** The size of the file just before the latest launch, once looked up; set on the supervisor's thread. */
    private volatile CompletableFuture<Long> sizeAtLaunch = CompletableFuture.completedFuture(0L);
    private final ReentrantLock checking = new ReentrantLock();
    // Guarded by checking: the invocation last checked and how far it has been read.
    private RunningApplication checked;
    private long position;
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    /** Whether the kept start of a line has matched already, so that the whole line does not count again. */
    private boolean partialMatched;
    /** The readiness check that first passed for the invocation, kept for its later readiness checks. */
    private ProbeResult ready;

    LogPatternProbe(Pattern pattern, Optional<Path> file) {
        this(pattern, file, Files::size, SIZE_WAIT);
    }

    LogPatternProbe(Pattern pattern, Optional<Path> file, FileSize fileSize, Duration sizeWait) {
        this.pattern = Objects.requireNonNull(pattern, "pattern");
        this.file = Objects.requireNonNull(file, "file");
        this.fileSize = Objects.requireNonNull(fileSize, "fileSize");
        this.sizeWait = Objects.requireNonNull(sizeWait, "sizeWait");
    }

    /**
     * The probe {@code config} describes.
     *
     * @throws IllegalArgumentException when {@code wrapper.readiness.log.pattern} is not set
     */
    static LogPatternProbe from(ProbeConfig config) {
        Pattern pattern = config.logPattern().orElseThrow(() -> new IllegalArgumentException("the " + TYPE
                + " probe needs " + ConfigKeys.READINESS_LOG_PATTERN.name()));
        return new LogPatternProbe(pattern, config.logFile());
    }

    /**
     * Notes the size of the file, so that what the new invocation appends is what counts; waits for it
     * at most {@link #SIZE_WAIT}.
     */
    @Override
    public void beforeLaunch() {
        if (file.isEmpty()) {
            return;
        }
        Path path = file.get();
        CompletableFuture<Long> size = new CompletableFuture<>();
        Thread.ofVirtual().name("nyajsw-log-probe-size").start(() -> {
            long found = 0; // a file that cannot be read: when it appears, all of it is new
            try {
                found = fileSize.of(path);
            } catch (IOException | RuntimeException e) {
                // as above
            } finally {
                size.complete(found); // even after an Error, so that the checks are not held up for good
            }
        });
        sizeAtLaunch = size;
        try {
            size.get(sizeWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // not known yet: checks fail until it is (it never completes exceptionally)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** It reads what came since its previous check, so it must read on while another probe fails. */
    @Override
    public boolean readsEveryCheck() {
        return true;
    }

    @Override
    public ProbeResult check(ProbeContext context) {
        if (!checking.tryLock()) {
            return ProbeResult.fail("the previous check of the log is still running");
        }
        try {
            if (checked != context.application()) {
                CompletableFuture<Long> size = sizeAtLaunch;
                if (file.isPresent() && !size.isDone()) {
                    return ProbeResult.fail("the size of " + file.get() + " before the launch is not known yet");
                }
                checked = context.application();
                position = file.isPresent() ? size.join() : 0;
                partial.reset();
                partialMatched = false;
                ready = null;
            }
            boolean readiness = context.purpose() == ProbeContext.Purpose.READINESS;
            if (readiness && ready != null) {
                return ready;
            }
            ProbeResult result = file.isPresent() ? checkFile(file.get()) : checkOutput(context.application());
            if (readiness && result.passed()) {
                ready = result;
            }
            return result;
        } finally {
            checking.unlock();
        }
    }

    private ProbeResult checkOutput(RunningApplication application) {
        OutputLines.Page page = application.output().since(position);
        position = page.next();
        String missed = page.skipped() == 0 ? "" : " (" + page.skipped() + " earlier lines were no longer kept)";
        for (String line : page.lines()) {
            if (pattern.matcher(line).find()) {
                return ProbeResult.pass(application.name() + " logged a line matching the pattern" + missed);
            }
        }
        return ProbeResult.fail("none of the " + page.lines().size() + " new lines of " + application.name()
                + " matches the pattern" + missed);
    }

    private ProbeResult checkFile(Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < position) {
                position = 0; // rotated or truncated: what is there now is new
                startLine();
            }
            if (size - position > MAX_READ) {
                position = size - MAX_READ;
                startLine();
            }
            return scan(channel, path, size);
        } catch (NoSuchFileException e) {
            position = 0; // when it appears, all of it is new
            startLine();
            return ProbeResult.fail(path + " does not exist");
        } catch (IOException e) {
            return ProbeResult.fail("cannot read " + path + ": " + e.getClass().getSimpleName());
        }
    }

    private void startLine() {
        partial.reset();
        partialMatched = false;
    }

    /** Reads from {@link #position} to {@code size}, line by line; a line without its end yet is kept for later. */
    private ProbeResult scan(FileChannel channel, Path path, long size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        boolean matched = false;
        int lines = 0;
        channel.position(position);
        while (position < size) {
            buffer.clear();
            int read = channel.read(buffer);
            if (read < 0) {
                break;
            }
            position += read;
            for (int i = 0; i < read; i++) {
                byte b = buffer.get(i);
                if (b == '\n') {
                    lines++;
                    matched |= !partialMatched && matches(partial);
                    startLine();
                } else if (partial.size() < MAX_LINE) {
                    partial.write(b);
                }
            }
        }
        if (partial.size() > 0 && !partialMatched && matches(partial)) {
            partialMatched = true; // the end of the line may still come; it does not count again
            matched = true;
        }
        return matched ? ProbeResult.pass(path + " has a new line matching the pattern")
                : ProbeResult.fail("none of the " + lines + " new lines of " + path + " matches the pattern");
    }

    private boolean matches(ByteArrayOutputStream line) {
        String text = line.toString(StandardCharsets.UTF_8);
        return pattern.matcher(text.endsWith("\r") ? text.substring(0, text.length() - 1) : text).find();
    }
}
