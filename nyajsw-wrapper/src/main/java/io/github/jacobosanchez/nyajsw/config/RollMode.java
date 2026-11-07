package io.github.jacobosanchez.nyajsw.config;

/**
 * When the wrapper log file rolls over ({@code wrapper.logfile.rollmode}): the values YAJSW
 * documents, plus the Tanuki values that files derived from Tanuki templates carry (D22); without a
 * mode it rolls by size only. Rolling by size ({@code wrapper.logfile.maxsize}) applies in every
 * mode but {@link #NONE}, although YAJSW gives {@code JVM} files no size limit (plan §3.4), so the
 * Tanuki {@code SIZE_OR_JVM} and {@code SIZE_OR_WRAPPER} are aliases of {@link #JVM} and {@link
 * #WRAPPER}. The constant names are the configuration values; {@link ConfigKeys} declares them from
 * this enum, so the spellings exist in one place.
 */
public enum RollMode {

    /** When the wrapper starts and before each restarted application JVM. */
    JVM,

    /** When the wrapper starts; YAJSW names this mode without describing it (plan P1.10). */
    WRAPPER,

    /** When the date in the log entries changes; the file name must contain {@code YYYYMMDD}. */
    DATE,

    /** By size only, as without a mode (Tanuki's value). */
    SIZE,

    /** Never, not even by size (Tanuki's value). */
    NONE
}
