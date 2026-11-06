package io.github.jacobosanchez.nyajsw.log;

import io.github.jacobosanchez.nyajsw.config.LogLevel;

import java.util.Objects;
import java.util.logging.Level;

/**
 * The YAJSW log levels as {@code java.util.logging} levels (plan §4.8, D6), so that handlers and
 * formatters see the names operators know ({@code STATUS}, {@code FATAL}) instead of {@code
 * WARNING} or {@code SEVERE}. The values keep the JDK's scale: {@code INFO} is {@link Level#INFO},
 * {@code DEBUG} sits at {@link Level#FINE}. Creating them registers the names in the JDK's table of
 * known levels, so {@code Level.parse("STATUS")} finds them; nothing else changes globally.
 */
final class WrapperLevel extends Level {

    private static final long serialVersionUID = 1L;

    static final WrapperLevel FATAL = new WrapperLevel("FATAL", 1100);
    static final WrapperLevel ERROR = new WrapperLevel("ERROR", Level.SEVERE.intValue());
    static final WrapperLevel STATUS = new WrapperLevel("STATUS", Level.WARNING.intValue());
    static final WrapperLevel INFO = new WrapperLevel("INFO", Level.INFO.intValue());
    static final WrapperLevel DEBUG = new WrapperLevel("DEBUG", Level.FINE.intValue());

    private WrapperLevel(String name, int value) {
        super(name, value);
    }

    /**
     * The level a message of {@code level} is logged at.
     *
     * @throws IllegalArgumentException for {@link LogLevel#NONE}, which is only a threshold
     */
    static Level of(LogLevel level) {
        return switch (Objects.requireNonNull(level, "level")) {
            case NONE -> throw new IllegalArgumentException("NONE is a threshold, not a message level");
            case FATAL -> FATAL;
            case ERROR -> ERROR;
            case STATUS -> STATUS;
            case INFO -> INFO;
            case DEBUG -> DEBUG;
        };
    }

    /** The lowest level that passes when {@code threshold} is configured; {@code NONE} lets nothing pass. */
    static Level threshold(LogLevel threshold) {
        return Objects.requireNonNull(threshold, "threshold") == LogLevel.NONE ? Level.OFF : of(threshold);
    }
}
