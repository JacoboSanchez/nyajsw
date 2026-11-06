package io.github.jacobosanchez.nyajsw.config;

import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.CONSOLE_FORMAT;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.CONSOLE_LOGLEVEL;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.DEBUG;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_DEBUG;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LOGFILE;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LOGFILE_FORMAT;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LOGFILE_LOGLEVEL;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LOGFILE_MAXFILES;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LOGFILE_MAXSIZE;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LOGFILE_ROLLMODE;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Wrapper logging as configured (plan §4.8). Settings without a declared default are empty; the
 * logging phase (P4) decides their behaviour and declares the defaults in the registry.
 *
 * @param logfile        {@code wrapper.logfile}, which may contain {@code ROLLNUM}/{@code YYYYMMDD}
 * @param logfileFormat  {@code wrapper.logfile.format}
 * @param logfileLevel   {@code wrapper.logfile.loglevel}
 * @param maxSizeBytes   {@code wrapper.logfile.maxsize} in bytes
 * @param maxFiles       {@code wrapper.logfile.maxfiles}
 * @param rollMode       {@code wrapper.logfile.rollmode}
 * @param consoleFormat  {@code wrapper.console.format}
 * @param consoleLevel   {@code wrapper.console.loglevel}
 * @param debug          {@code wrapper.debug}
 * @param javaDebug      {@code wrapper.java.debug}
 */
public record LogConfig(Optional<Path> logfile, Optional<String> logfileFormat, Optional<LogLevel> logfileLevel,
        Optional<Long> maxSizeBytes, Optional<Integer> maxFiles, Optional<RollMode> rollMode,
        Optional<String> consoleFormat, Optional<LogLevel> consoleLevel, boolean debug, boolean javaDebug) {

    public LogConfig {
        Objects.requireNonNull(logfile, "logfile");
        Objects.requireNonNull(logfileFormat, "logfileFormat");
        Objects.requireNonNull(logfileLevel, "logfileLevel");
        Objects.requireNonNull(maxSizeBytes, "maxSizeBytes");
        Objects.requireNonNull(maxFiles, "maxFiles");
        Objects.requireNonNull(rollMode, "rollMode");
        Objects.requireNonNull(consoleFormat, "consoleFormat");
        Objects.requireNonNull(consoleLevel, "consoleLevel");
    }

    /**
     * Hides values: the log file name could contain a secret copied in with <code>${...}</code>.
     * {@link ConfigDump} is the sanctioned way to show values.
     */
    @Override
    public String toString() {
        return "LogConfig[values hidden; see ConfigDump]";
    }

    static LogConfig from(ConfigValues values) {
        return new LogConfig(
                values.path(LOGFILE),
                values.text(LOGFILE_FORMAT),
                values.enumeration(LOGFILE_LOGLEVEL, LogLevel.class),
                values.size(LOGFILE_MAXSIZE),
                values.integer(LOGFILE_MAXFILES),
                values.enumeration(LOGFILE_ROLLMODE, RollMode.class),
                values.text(CONSOLE_FORMAT),
                values.enumeration(CONSOLE_LOGLEVEL, LogLevel.class),
                values.required(values.bool(DEBUG), DEBUG),
                values.required(values.bool(JAVA_DEBUG), JAVA_DEBUG));
    }
}
