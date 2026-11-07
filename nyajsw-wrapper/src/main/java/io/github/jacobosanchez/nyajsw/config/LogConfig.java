package io.github.jacobosanchez.nyajsw.config;

import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.CONSOLE_FORMAT;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.CONSOLE_LOGLEVEL;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.DEBUG;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.DEBUG_LEVEL;
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
 * @param logfileFormat  {@code wrapper.logfile.format}; empty when blank, which turns the file off
 * @param logfileLevel   {@code wrapper.logfile.loglevel}
 * @param maxSizeBytes   {@code wrapper.logfile.maxsize} in bytes
 * @param maxFiles       {@code wrapper.logfile.maxfiles}
 * @param rollMode       {@code wrapper.logfile.rollmode}
 * @param consoleFormat  {@code wrapper.console.format}; empty when blank, which turns the console off
 * @param consoleLevel   {@code wrapper.console.loglevel}
 * @param debug          {@code wrapper.debug}
 * @param debugLevel     {@code wrapper.debug.level} as configured; {@link #debugDetail()} is what applies
 */
public record LogConfig(Optional<Path> logfile, Optional<LineFormat> logfileFormat, Optional<LogLevel> logfileLevel,
        Optional<Long> maxSizeBytes, Optional<Integer> maxFiles, Optional<RollMode> rollMode,
        Optional<LineFormat> consoleFormat, Optional<LogLevel> consoleLevel, boolean debug, DebugDetail debugLevel) {

    public LogConfig {
        Objects.requireNonNull(logfile, "logfile");
        Objects.requireNonNull(logfileFormat, "logfileFormat");
        Objects.requireNonNull(logfileLevel, "logfileLevel");
        Objects.requireNonNull(maxSizeBytes, "maxSizeBytes");
        Objects.requireNonNull(maxFiles, "maxFiles");
        Objects.requireNonNull(rollMode, "rollMode");
        Objects.requireNonNull(consoleFormat, "consoleFormat");
        Objects.requireNonNull(consoleLevel, "consoleLevel");
        Objects.requireNonNull(debugLevel, "debugLevel");
    }

    /**
     * The lowest level written to the console: {@code wrapper.console.loglevel}, raised to {@code
     * DEBUG} by {@code wrapper.debug}.
     */
    public LogLevel consoleThreshold() {
        // The registry gives wrapper.console.loglevel a default, so it is always there.
        return debug ? LogLevel.DEBUG : consoleLevel.orElseThrow();
    }

    /**
     * Which debug messages are written, by their detail: {@code wrapper.debug.level} when {@code
     * wrapper.debug} is on (YAJSW reads the level only then); otherwise all of them, for a sink
     * whose own level is {@code DEBUG}.
     */
    public DebugDetail debugDetail() {
        return debug ? debugLevel : DebugDetail.ALL;
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
                format(values, LOGFILE_FORMAT),
                values.enumeration(LOGFILE_LOGLEVEL, LogLevel.class),
                values.size(LOGFILE_MAXSIZE),
                values.integer(LOGFILE_MAXFILES),
                values.enumeration(LOGFILE_ROLLMODE, RollMode.class),
                format(values, CONSOLE_FORMAT),
                values.enumeration(CONSOLE_LOGLEVEL, LogLevel.class),
                values.required(values.bool(DEBUG), DEBUG),
                debugLevel(values, values.required(values.bool(DEBUG), DEBUG)));
    }

    /**
     * {@code wrapper.debug.level}; a value outside the scale is an error only where it would take
     * effect, under {@code wrapper.debug}, so that a leftover in a file that does not debug cannot
     * stop the wrapper.
     */
    /**
     * A format key; what is wrong with its letters is a warning, since YAJSW gives no rule for it and
     * a line with the remaining fields still says what happened.
     */
    private static Optional<LineFormat> format(ConfigValues values, KeySpec spec) {
        Optional<String> text = values.text(spec);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        LineFormat.Parsed parsed = LineFormat.parse(text.get());
        ConfigLocation location = values.location(spec).orElse(ConfigLocation.of("built-in default"));
        parsed.problems().forEach(problem -> values.warn(location, spec.name() + ": " + problem));
        return parsed.format();
    }

    private static DebugDetail debugLevel(ConfigValues values, boolean debug) {
        int level = values.required(values.integer(DEBUG_LEVEL), DEBUG_LEVEL);
        if (DebugDetail.isLevel(level)) {
            return DebugDetail.ofLevel(level);
        }
        ConfigLocation location = values.location(DEBUG_LEVEL).orElseThrow();
        String problem = DEBUG_LEVEL.name() + "=" + level + " is not between 0 and " + DebugDetail.ALL.ordinal();
        if (debug) {
            values.problem(DEBUG_LEVEL, location, problem);
        } else {
            values.warn(location, problem + "; it has no effect while " + DEBUG.name() + " is false");
        }
        return values.constantDefault(DEBUG_LEVEL, text -> DebugDetail.ofLevel(ValueParser.parseInteger(text)));
    }
}
