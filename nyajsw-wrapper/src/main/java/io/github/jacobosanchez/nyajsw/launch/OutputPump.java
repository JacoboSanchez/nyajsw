package io.github.jacobosanchez.nyajsw.launch;

import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.config.SecretMasker;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Copies one output stream of the application into the wrapper log, line by line, until the stream
 * ends (plan §4.8). It runs on its own thread so that the application never blocks on a full pipe,
 * and it keeps reading whatever happens to a line: a line longer than {@link #MAX_LINE} characters
 * is logged in pieces, each after the first marked with {@link #CONTINUED}, so output without line
 * breaks cannot fill the wrapper's memory; a log sink that fails does not stop the pump. The log it is
 * given names the application ({@code jvm 1}), so captured lines can be told from the wrapper's own.
 *
 * <p>Every line is masked with the configuration's {@link SecretMasker} before it is logged, since an
 * application may echo an option that holds a password (the JVM prints an unknown {@code -X} option
 * back, for instance). A long line is never cut inside a secret, and enough of its end is kept for
 * the next piece that a secret starting there is masked whole.
 */
final class OutputPump implements Runnable {

    /** The longest piece of a line logged at once. */
    static final int MAX_LINE = 16 * 1024;

    /** Starts the pieces of a line that was longer than {@link #MAX_LINE}. */
    static final String CONTINUED = "... ";

    private final InputStream in;
    private final Charset charset;
    private final WrapperLog log;
    private final LogLevel level;
    private final SecretMasker secrets;
    /** Told every line as it is logged, masked. */
    private final Consumer<String> lines;
    /** Characters kept back when a long line is cut, so a secret that starts there stays whole. */
    private final int keep;

    OutputPump(InputStream in, Charset charset, WrapperLog log, LogLevel level, SecretMasker secrets) {
        this(in, charset, log, level, secrets, line -> { });
    }

    /** @param lines told every line as it is logged, masked; it must not block */
    OutputPump(InputStream in, Charset charset, WrapperLog log, LogLevel level, SecretMasker secrets,
            Consumer<String> lines) {
        this.in = Objects.requireNonNull(in, "in");
        this.charset = Objects.requireNonNull(charset, "charset");
        this.log = Objects.requireNonNull(log, "log");
        this.level = Objects.requireNonNull(level, "level");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.lines = Objects.requireNonNull(lines, "lines");
        this.keep = Math.min(Math.max(0, secrets.longestSecret() - 1), MAX_LINE / 2);
    }

    @Override
    public void run() {
        StringBuilder line = new StringBuilder();
        boolean continued = false;
        boolean afterCarriageReturn = false;
        char[] buffer = new char[4096];
        try (Reader reader = new InputStreamReader(in, charset)) {
            for (int read = reader.read(buffer); read >= 0; read = reader.read(buffer)) {
                for (int i = 0; i < read; i++) {
                    char c = buffer[i];
                    if (c == '\n' && afterCarriageReturn) {
                        afterCarriageReturn = false; // the second half of \r\n
                        continue;
                    }
                    afterCarriageReturn = c == '\r';
                    if (c == '\n' || c == '\r') {
                        if (!(continued && line.isEmpty())) {
                            emit(line, continued); // not after a piece that ended exactly at the break
                        }
                        continued = false;
                    } else {
                        line.append(c);
                        if (line.length() == MAX_LINE) {
                            emitPiece(line, continued);
                            continued = true;
                        }
                    }
                }
            }
        } catch (IOException e) {
            // The stream closes under us when the process is killed; nothing readable is lost.
            safely(() -> log.debug(() -> "output of " + log.source() + " ended: " + e.getMessage()));
        }
        if (!line.isEmpty()) {
            emit(line, continued);
        }
    }

    private void emit(StringBuilder line, boolean continued) {
        String text = secrets.maskText(line.toString());
        line.setLength(0);
        String logged = continued ? CONTINUED + text : text;
        safely(() -> lines.accept(logged));
        safely(() -> log.log(level, logged));
    }

    /** Logs the first part of a full line, cut where no secret is split, and keeps the rest. */
    private void emitPiece(StringBuilder line, boolean continued) {
        String text = line.toString();
        int cut = secrets.safeCut(text, MAX_LINE - keep);
        if (cut == 0) {
            cut = MAX_LINE; // a secret longer than half a line: nothing better to do than a whole piece
        }
        String piece = secrets.maskText(text.substring(0, cut));
        line.delete(0, cut);
        String logged = continued ? CONTINUED + piece : piece;
        safely(() -> lines.accept(logged));
        safely(() -> log.log(level, logged));
    }

    /** A failing sink must not stop the pump: the pipe would fill and block the application. */
    private static void safely(Runnable logging) {
        try {
            logging.run();
        } catch (RuntimeException e) {
            // Nowhere left to report it; the sink's own error handling has had its chance.
        }
    }
}
