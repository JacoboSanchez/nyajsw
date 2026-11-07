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
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.LOG_ENCODING;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Wrapper logging as configured (plan §4.8). Settings without a declared default are empty.
 *
 * @param logfile        {@code wrapper.logfile}, which may contain {@code ROLLNUM}/{@code YYYYMMDD};
 *                       {@code wrapper.log} beside the configuration file when not set, empty when
 *                       written blank, which turns the file off
 * @param logfileFormat  {@code wrapper.logfile.format}; empty when blank, which turns the file off
 * @param logfileLevel   {@code wrapper.logfile.loglevel}
 * @param maxSizeBytes   {@code wrapper.logfile.maxsize} in bytes; 0 rolls by size never
 * @param maxFiles       {@code wrapper.logfile.maxfiles}; empty or 0 keeps every rolled file
 * @param rollMode       {@code wrapper.logfile.rollmode}
 * @param consoleFormat  {@code wrapper.console.format}; empty when blank, which turns the console off
 * @param consoleLevel   {@code wrapper.console.loglevel}
 * @param debug          {@code wrapper.debug}
 * @param debugLevel     {@code wrapper.debug.level} as configured; {@link #debugDetail()} is what applies
 * @param encoding       {@code wrapper.log.encoding}, the JVM's default charset when not set
 */
public record LogConfig(Optional<Path> logfile, Optional<LineFormat> logfileFormat, Optional<LogLevel> logfileLevel,
        Optional<Long> maxSizeBytes, Optional<Integer> maxFiles, Optional<RollMode> rollMode,
        Optional<LineFormat> consoleFormat, Optional<LogLevel> consoleLevel, boolean debug, DebugDetail debugLevel,
        Charset encoding) {

    /** The log file when {@code wrapper.logfile} is not set, in the configuration file's directory (D13). */
    static final String DEFAULT_LOGFILE = "wrapper.log";

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
        Objects.requireNonNull(encoding, "encoding");
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
     * The lowest level written to the log file: {@code wrapper.logfile.loglevel}, raised to {@code
     * DEBUG} by {@code wrapper.debug}.
     */
    public LogLevel logfileThreshold() {
        // The registry gives wrapper.logfile.loglevel a default, so it is always there.
        return debug ? LogLevel.DEBUG : logfileLevel.orElseThrow();
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
        boolean debug = values.required(values.bool(DEBUG), DEBUG);
        return new LogConfig(
                logfile(values),
                format(values, LOGFILE_FORMAT),
                values.enumeration(LOGFILE_LOGLEVEL, LogLevel.class),
                values.size(LOGFILE_MAXSIZE),
                maxFiles(values),
                values.enumeration(LOGFILE_ROLLMODE, RollMode.class),
                format(values, CONSOLE_FORMAT),
                values.enumeration(CONSOLE_LOGLEVEL, LogLevel.class),
                debug,
                debugLevel(values, debug),
                encoding(values));
    }

    /** YAJSW turns the file off for a blank {@code wrapper.logfile}; not setting it gives the default file. */
    private static Optional<Path> logfile(ConfigValues values) {
        if (values.isBlank(LOGFILE)) {
            return Optional.empty();
        }
        if (values.location(LOGFILE).isEmpty()) {
            return Optional.of(values.directory().resolve(DEFAULT_LOGFILE));
        }
        Optional<Path> file = values.path(LOGFILE);
        if (file.isPresent()) {
            try {
                new LogFileNames(file.get());
            } catch (IllegalArgumentException e) {
                values.problem(LOGFILE, values.location(LOGFILE).orElseThrow(),
                        LOGFILE.name() + ": " + e.getMessage());
                return Optional.empty();
            }
        }
        return file;
    }

    private static Optional<Integer> maxFiles(ConfigValues values) {
        Optional<Integer> maxFiles = values.integer(LOGFILE_MAXFILES);
        if (maxFiles.isPresent() && maxFiles.get() < 0) {
            values.problem(LOGFILE_MAXFILES, values.location(LOGFILE_MAXFILES).orElseThrow(),
                    LOGFILE_MAXFILES.name() + "=" + maxFiles.get() + " is negative");
            return Optional.empty();
        }
        return maxFiles;
    }

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

    /**
     * {@code wrapper.debug.level}; a value outside the scale is an error only where it would take
     * effect, under {@code wrapper.debug}, so that a leftover in a file that does not debug cannot
     * stop the wrapper.
     */
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

    /** {@code wrapper.log.encoding}; a name the JVM does not know is a problem of the key. */
    private static Charset encoding(ConfigValues values) {
        Optional<String> name = values.text(LOG_ENCODING).map(String::strip).filter(text -> !text.isEmpty());
        if (name.isEmpty()) {
            return Charset.defaultCharset();
        }
        try {
            return Charset.forName(name.get());
        } catch (IllegalArgumentException e) { // IllegalCharsetNameException, UnsupportedCharsetException
            values.problem(LOG_ENCODING, values.location(LOG_ENCODING).orElseThrow(), "unknown character encoding '"
                    + values.shown(LOG_ENCODING.name(), name.get()) + "' for " + LOG_ENCODING.name());
            return Charset.defaultCharset();
        }
    }
}
