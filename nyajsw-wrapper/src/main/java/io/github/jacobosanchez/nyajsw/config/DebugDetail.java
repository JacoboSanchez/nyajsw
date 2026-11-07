package io.github.jacobosanchez.nyajsw.config;

/**
 * How much of the wrapper's {@code DEBUG} output is written, from least to most: the scale of
 * {@code wrapper.debug.level}, whose value is the ordinal (plan §4.8). YAJSW documents level 0 as
 * the application's pid and its stop only, which nyajsw logs above {@code DEBUG} anyway, 1 as
 * debug output about starting and stopping the application, 2 as some more, and 3 as everything;
 * nyajsw files each debug message under one of these.
 */
public enum DebugDetail {

    /** No debug message; only a threshold, never the detail of a message. */
    NONE,

    /** Starting and stopping the application: state changes, an application ending during a stop. */
    APPLICATION,

    /** The steps of supervision: stop steps, readiness checks, pid files. */
    SUPERVISION,

    /** Everything, down to closed streams and timers. */
    ALL;

    /** Whether {@code level} is a value of {@code wrapper.debug.level}, 0 to {@code ALL.ordinal()}. */
    static boolean isLevel(int level) {
        return level >= 0 && level <= ALL.ordinal();
    }

    /**
     * The detail {@code wrapper.debug.level} stands for.
     *
     * @throws IllegalArgumentException when {@link #isLevel} is false
     */
    static DebugDetail ofLevel(int level) {
        if (!isLevel(level)) {
            throw new IllegalArgumentException("debug level " + level + " is not between 0 and " + ALL.ordinal());
        }
        return values()[level];
    }
}
