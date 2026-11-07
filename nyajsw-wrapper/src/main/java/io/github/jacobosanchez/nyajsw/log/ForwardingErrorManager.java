package io.github.jacobosanchez.nyajsw.log;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.ErrorManager;

/**
 * Passes the failures of a sink (the log file locked, the disk full) on to somewhere they can be
 * seen: the console, standard error, or the bootstrap log. The JDK's own error manager prints only
 * the first failure of a sink, ever; this one prints every failure, but one that repeats the one
 * before it (every record failing on a full disk) again only after {@link #REPEAT_AFTER}, with the
 * number of repeats it held back, so that a failure coming back days later is seen again.
 *
 * <p>An exception is shown by its class only: its message could repeat a file name the sink has
 * masked in its own message.
 */
final class ForwardingErrorManager extends ErrorManager {

    /** How long a repeated failure is held back. */
    static final Duration REPEAT_AFTER = Duration.ofMinutes(10);

    private final Consumer<String> target;
    private final LongSupplier nanoTicker;
    private String last;
    private long lastReportedAt;
    private int heldBack;

    ForwardingErrorManager(Consumer<String> target) {
        this(target, System::nanoTime);
    }

    /** With {@code nanoTicker}, a monotonic time in nanoseconds, for tests. */
    ForwardingErrorManager(Consumer<String> target, LongSupplier nanoTicker) {
        this.target = Objects.requireNonNull(target, "target");
        this.nanoTicker = Objects.requireNonNull(nanoTicker, "nanoTicker");
    }

    @Override
    public synchronized void error(String message, Exception error, int code) {
        String text = message != null ? message : "a log record could not be written";
        if (error != null) {
            text += " (" + error.getClass().getName() + ")";
        }
        long now = nanoTicker.getAsLong();
        boolean repeat = text.equals(last);
        if (repeat && now - lastReportedAt < REPEAT_AFTER.toNanos()) {
            heldBack++;
            return;
        }
        String shown = repeat && heldBack > 0 ? text + " (" + heldBack + " more times since it was last shown)" : text;
        last = text;
        lastReportedAt = now;
        heldBack = 0;
        try {
            target.accept(shown);
        } catch (RuntimeException e) {
            // Nowhere left to report to; the sink goes on.
        }
    }
}
