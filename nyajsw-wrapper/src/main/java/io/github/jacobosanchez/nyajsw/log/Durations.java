package io.github.jacobosanchez.nyajsw.log;

import java.time.Duration;
import java.util.Locale;

/** How log messages show a duration, so that every message shows it the same way. */
public final class Durations {

    private Durations() {
    }

    /** {@code 30 s}, or with milliseconds when the duration has them ({@code 7.500 s}). */
    public static String seconds(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1000 == 0 ? millis / 1000 + " s" : String.format(Locale.ROOT, "%.3f s", millis / 1000.0);
    }
}
