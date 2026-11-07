package io.github.jacobosanchez.nyajsw.config;

import static io.github.jacobosanchez.nyajsw.config.KeyCategory.CONFIGURATION;
import static io.github.jacobosanchez.nyajsw.config.KeyCategory.JAVA;
import static io.github.jacobosanchez.nyajsw.config.KeyCategory.LIFECYCLE;
import static io.github.jacobosanchez.nyajsw.config.KeyCategory.LOGGING;
import static io.github.jacobosanchez.nyajsw.config.KeyCategory.OUT_OF_SCOPE;
import static io.github.jacobosanchez.nyajsw.config.KeyCategory.PROBES;
import static io.github.jacobosanchez.nyajsw.config.KeyCategory.SERVICE;
import static io.github.jacobosanchez.nyajsw.config.KeySpec.nyajsw;
import static io.github.jacobosanchez.nyajsw.config.KeySpec.yajsw;
import static io.github.jacobosanchez.nyajsw.config.KeyStatus.IGNORED;
import static io.github.jacobosanchez.nyajsw.config.KeyStatus.PLANNED;
import static io.github.jacobosanchez.nyajsw.config.KeyStatus.SUPPORTED;
import static io.github.jacobosanchez.nyajsw.config.KeyStatus.UNSUPPORTED;
import static io.github.jacobosanchez.nyajsw.config.KeyStatus.UNSUPPORTED_FATAL;
import static io.github.jacobosanchez.nyajsw.config.KeyType.BOOLEAN;
import static io.github.jacobosanchez.nyajsw.config.KeyType.DURATION;
import static io.github.jacobosanchez.nyajsw.config.KeyType.ENUM;
import static io.github.jacobosanchez.nyajsw.config.KeyType.INTEGER;
import static io.github.jacobosanchez.nyajsw.config.KeyType.LIST;
import static io.github.jacobosanchez.nyajsw.config.KeyType.PATH;
import static io.github.jacobosanchez.nyajsw.config.KeyType.SIZE;
import static io.github.jacobosanchez.nyajsw.config.KeyType.STRING;
import static io.github.jacobosanchez.nyajsw.config.Unverified.DEFAULT;
import static io.github.jacobosanchez.nyajsw.config.Unverified.MEANING;
import static io.github.jacobosanchez.nyajsw.config.Unverified.NAME;
import static io.github.jacobosanchez.nyajsw.config.Unverified.VALUES;

import java.util.Arrays;
import java.util.List;

/**
 * The key registry: the single declaration of every configuration key nyajsw knows (plan §4.5,
 * decision D8, AGENTS.md §4.6). Parsing, validation, warnings, secret masking and
 * {@code docs/COMPATIBILITY.md} are derived from it; code elsewhere refers to keys through these
 * constants instead of repeating their names.
 *
 * <p>Statuses and phases are seeded from the plan's initial compatibility matrix (§7); names,
 * defaults and values were checked against the public YAJSW reference (plan P1.10).
 * {@link KeySpec#unverified()} marks the parts that reference does not settle, which still have to
 * be confirmed (risk R11) before the key is implemented. Keys without a declared default get one, when they need
 * it, in the phase that implements them.
 *
 * <p>To add a key: declare a constant here and add it to {@link #REGISTRY} in the same category
 * block. A test fails when a constant is missing from the registry.
 */
public final class ConfigKeys {

    private static final String P9_BACKLOG = "P9";
    private static final List<String> LOG_LEVELS = names(LogLevel.class);
    private static final List<String> EXIT_ACTIONS = names(ExitAction.class);
    private static final String STOP_ACTION_NOTE = "STOP keeps the wrapper running only when wrapper.control is LOOSE"
            + " or APPLICATION; otherwise it ends the wrapper like SHUTDOWN (D20)";

    // ---- configuration file ----

    public static final KeySpec CONF_ENCODING = yajsw("wrapper.conf.encoding", STRING, CONFIGURATION)
            .status(SUPPORTED)
            .description("Character encoding of the configuration file and its includes")
            .derivedDefault("UTF-8, with a windows-1252 fallback and a warning for files that are not valid"
                    + " UTF-8 (D7)")
            .notes("A Java charset name, without variables. Set by a system property (required for UTF-16) or"
                    + " in the file itself. Decoding is strict: a byte that does not fit is an error at its line."
                    + " YAJSW defaults to the platform charset instead")
            .build();

    public static final KeySpec CONFIG_SCRIPT = yajsw("wrapper.config.script.<name>", STRING, CONFIGURATION)
            .status(UNSUPPORTED_FATAL)
            .description("Groovy script that transforms configuration values, such as a password decryptor")
            .notes("Fatal because ignoring it would pass on values that were meant to be transformed first")
            .build();

    // ---- Java process ----

    public static final KeySpec JAVA_COMMAND = yajsw("wrapper.java.command", PATH, JAVA)
            .status(SUPPORTED)
            .description("Java executable that runs the application")
            .derivedDefault("java from the wrapper JVM's java.home, or javaw when wrapper.java.command.javaw is true")
            .build();

    public static final KeySpec JAVA_COMMAND_JAVAW = yajsw("wrapper.java.command.javaw", BOOLEAN, JAVA)
            .status(SUPPORTED)
            .description("Use javaw instead of java for the default Java executable")
            .defaultValue("false")
            .toVerify(DEFAULT)
            .build();

    public static final KeySpec JAVA_COMMAND_MIN_VERSION = yajsw("wrapper.java.command.minVersion", STRING, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Lowest Java version accepted when searching for a JVM")
            .build();

    public static final KeySpec JAVA_COMMAND_MAX_VERSION = yajsw("wrapper.java.command.maxVersion", STRING, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Highest Java version accepted when searching for a JVM")
            .build();

    public static final KeySpec JAVA_COMMAND_JRE_ONLY = yajsw("wrapper.java.command.jreOnly", BOOLEAN, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Accept only a JRE when searching for a JVM")
            .build();

    public static final KeySpec JAVA_COMMAND_JDK_ONLY = yajsw("wrapper.java.command.jdkOnly", BOOLEAN, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Accept only a JDK when searching for a JVM")
            .build();

    public static final KeySpec JAVA_COMMAND_PREFER_JRE = yajsw("wrapper.java.command.preferJre", BOOLEAN, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Prefer a JRE when searching for a JVM")
            .build();

    public static final KeySpec JAVA_COMMAND_PREFER_JDK = yajsw("wrapper.java.command.preferJdk", BOOLEAN, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Prefer a JDK when searching for a JVM")
            .build();

    public static final KeySpec JAVA_CUSTOM_PROC_NAME = yajsw("wrapper.java.customProcName", STRING, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Run the application from a renamed copy of the Java executable")
            .build();

    public static final KeySpec JAVA_APP_MAINCLASS = yajsw("wrapper.java.app.mainclass", STRING, JAVA)
            .status(SUPPORTED)
            .description("Main class of the application")
            .build();

    public static final KeySpec JAVA_APP_JAR = yajsw("wrapper.java.app.jar", PATH, JAVA)
            .status(SUPPORTED)
            .description("Executable jar of the application, instead of a main class")
            .build();

    public static final KeySpec JAVA_MAINCLASS = yajsw("wrapper.java.mainclass", STRING, JAVA)
            .status(IGNORED)
            .description("YAJSW's in-JVM bootstrap class")
            .notes("nyajsw starts the application main class directly; the optional agent replaces the bootstrap")
            .build();

    public static final KeySpec APP_PARAMETER = yajsw("wrapper.app.parameter.<n>", STRING, JAVA)
            .status(SUPPORTED)
            .description("Program argument passed to the application")
            .build();

    public static final KeySpec APP_PARAMETER_STRIPQUOTES =
            yajsw("wrapper.app.parameter.<n>.stripquotes", BOOLEAN, JAVA)
                    .status(UNSUPPORTED)
                    .description("Tanuki: remove quotes from wrapper.app.parameter.<n>")
                    .notes("Declared so that it is not read as a program argument")
                    .build();

    public static final KeySpec JAVA_CLASSPATH = yajsw("wrapper.java.classpath.<n>", PATH, JAVA)
            .status(SUPPORTED)
            .description("Classpath entry of the application; * and ? wildcards as in YAJSW")
            .notes("A whole ** segment matches any depth, a nyajsw extension; matches are sorted by path")
            .build();

    public static final KeySpec JAVA_LIBRARY_PATH = yajsw("wrapper.java.library.path.<n>", PATH, JAVA)
            .status(SUPPORTED)
            .description("Entry of the application's java.library.path")
            .notes("May list several paths separated by ;, as when it copies ${path}; an invalid one in such a"
                    + " list is skipped with a warning")
            .build();

    public static final KeySpec JAVA_CLASSPATH_USE_ENVIRONMENT =
            yajsw("wrapper.java.classpath.use_environment", BOOLEAN, JAVA)
                    .status(UNSUPPORTED)
                    .description("Tanuki: append the CLASSPATH environment variable to the classpath")
                    .notes("Declared so that it is not read as a classpath entry")
                    .build();

    public static final KeySpec JAVA_CLASSPATH_MISSING_LOG_LEVEL =
            yajsw("wrapper.java.classpath.missing_log_level", STRING, JAVA)
                    .status(UNSUPPORTED)
                    .description("Tanuki: log level used when a classpath entry does not exist")
                    .notes("Declared so that it is not read as a classpath entry")
                    .build();

    public static final KeySpec JAVA_LIBRARY_PATH_APPEND_SYSTEM_PATH =
            yajsw("wrapper.java.library.path.append_system_path", BOOLEAN, JAVA)
                    .status(UNSUPPORTED)
                    .description("Tanuki: append the system PATH to java.library.path")
                    .notes("Declared so that it is not read as a library path entry")
                    .build();

    public static final KeySpec JAVA_ADDITIONAL = yajsw("wrapper.java.additional.<n>", STRING, JAVA)
            .status(SUPPORTED)
            .description("JVM option or -D system property of the application")
            .build();

    public static final KeySpec JAVA_ADDITIONAL_AUTO_BITS = yajsw("wrapper.java.additional.auto_bits", BOOLEAN, JAVA)
            .status(UNSUPPORTED)
            .description("Tanuki: add -d32 or -d64 to the JVM options to match the wrapper")
            .notes("Declared so that it is not read as a JVM option")
            .build();

    public static final KeySpec JAVA_ADDITIONAL_AUTO_BITS_PLATFORM =
            yajsw("wrapper.java.additional.auto_bits.<platform>", BOOLEAN, JAVA)
                    .status(UNSUPPORTED)
                    .description("Tanuki: wrapper.java.additional.auto_bits for one platform")
                    .notes("Declared so that it is not read as a JVM option")
                    .build();

    public static final KeySpec JAVA_ADDITIONAL_STRIPQUOTES =
            yajsw("wrapper.java.additional.<n>.stripquotes", BOOLEAN, JAVA)
                    .status(UNSUPPORTED)
                    .description("Tanuki: remove quotes from wrapper.java.additional.<n>")
                    .notes("Declared so that it is not read as a JVM option")
                    .build();

    public static final KeySpec JAVA_INITMEMORY = yajsw("wrapper.java.initmemory", INTEGER, JAVA)
            .status(SUPPORTED)
            .description("Initial heap of the application in MB (-Xms)")
            .build();

    public static final KeySpec JAVA_MAXMEMORY = yajsw("wrapper.java.maxmemory", INTEGER, JAVA)
            .status(SUPPORTED)
            .description("Maximum heap of the application in MB (-Xmx)")
            .build();

    public static final KeySpec JAVA_INITMEMORY_RELATIVE = yajsw("wrapper.java.initmemory.relative", INTEGER, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Initial heap as a percentage of physical memory")
            .build();

    public static final KeySpec JAVA_INITIALMEMORY_RELATIVE =
            yajsw("wrapper.java.initialmemory.relative", INTEGER, JAVA)
                    .status(UNSUPPORTED, P9_BACKLOG)
                    .description("Initial heap as a percentage of physical memory")
                    .notes("The spelling of the YAJSW reference; wrapper.java.initmemory.relative is the other one")
                    .build();

    public static final KeySpec JAVA_MAXMEMORY_RELATIVE = yajsw("wrapper.java.maxmemory.relative", INTEGER, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Maximum heap as a percentage of physical memory")
            .build();

    public static final KeySpec APP_ENV = yajsw("wrapper.app.env.<variable>", STRING, JAVA)
            .status(SUPPORTED)
            .description("Environment variable set for the application")
            .notes("An empty value removes the inherited variable; on Windows every spelling of the name is replaced")
            .build();

    public static final KeySpec WORKING_DIR = yajsw("wrapper.working.dir", PATH, JAVA)
            .status(SUPPORTED)
            .description("Working directory of the application")
            .derivedDefault("the directory of the configuration file (D13)")
            .toVerify(DEFAULT)
            .build();

    public static final KeySpec TMP_PATH = yajsw("wrapper.tmp.path", PATH, JAVA)
            .status(SUPPORTED)
            .description("Temporary directory of the wrapper, also used as jna.tmpdir")
            .notes("Not passed to the application. Written into the service's command line as -Djna.tmpdir,"
                    + " and set as jna.tmpdir for the service commands unless their command line sets it")
            .toVerify(DEFAULT) // the reference says /tmp, YAJSW's change log jna.tmpdir under its tmp folder
            .build();

    public static final KeySpec CLEANUP_TMP = yajsw("wrapper.cleanup_tmp", BOOLEAN, JAVA)
            .status(UNSUPPORTED)
            .description("Delete the wrapper's leftover output-buffer files from the temporary directory at start")
            .notes("YAJSW default: true")
            .build();

    public static final KeySpec IMAGE = yajsw("wrapper.image", PATH, JAVA)
            .status(PLANNED, "P2")
            .description("Non-Java executable to run instead of a JVM")
            .notes("Optional plan item P2.6")
            .build();

    public static final KeySpec PRIORITY = yajsw("wrapper.priority", STRING, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Process priority of the application")
            .build();

    public static final KeySpec AFFINITY = yajsw("wrapper.affinity", STRING, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("CPU affinity of the application")
            .build();

    public static final KeySpec APP_ACCOUNT = yajsw("wrapper.app.account", STRING, JAVA)
            .status(UNSUPPORTED_FATAL, P9_BACKLOG)
            .description("Run the application as another user")
            .notes("Fatal because ignoring it would run the application under the wrong account")
            .build();

    public static final KeySpec APP_PASSWORD = yajsw("wrapper.app.password", STRING, JAVA)
            .status(UNSUPPORTED_FATAL, P9_BACKLOG)
            .secret()
            .description("Password of wrapper.app.account")
            .build();

    public static final KeySpec JAVA_MONITOR = yajsw("wrapper.java.monitor.<setting>", STRING, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Deadlock, heap and garbage-collection monitoring (deadlock, heap, gc, with .interval,"
                    + " .restart, heap.threshold.percent and gc.threshold)")
            .build();

    public static final KeySpec JAVA_DEBUG_PORT = yajsw("wrapper.java.debug.port", INTEGER, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Debug port of the application; adds the JDWP options and lifts the wrapper's timeouts")
            .build();

    public static final KeySpec JAVA_JMX = yajsw("wrapper.java.jmx", BOOLEAN, JAVA)
            .status(UNSUPPORTED)
            .description("Register YAJSW's wrapper MBean with the application's MBean server")
            .build();

    public static final KeySpec REGISTRY_JAVA_HOME = yajsw("wrapper.registry.java_home", STRING, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Take the Java home of the application from the Windows registry")
            .toVerify(MEANING)
            .build();

    public static final KeySpec APP_PRE_SCRIPT = yajsw("wrapper.app.pre.script", STRING, JAVA)
            .status(UNSUPPORTED)
            .description("Groovy script run before the application starts")
            .build();

    public static final KeySpec APP_PRE_MAIN_SCRIPT = yajsw("wrapper.app.pre_main.script", STRING, JAVA)
            .status(UNSUPPORTED)
            .description("Groovy script run in the application JVM before its main method")
            .build();

    public static final KeySpec CODEBASE = yajsw("wrapper.codebase", STRING, JAVA)
            .status(UNSUPPORTED)
            .description("Network launching: base URL the application's files are downloaded from")
            .build();

    public static final KeySpec CACHE = yajsw("wrapper.cache", PATH, JAVA)
            .status(UNSUPPORTED)
            .description("Network launching: directory the downloaded files are cached in")
            .build();

    public static final KeySpec CACHE_LOCAL = yajsw("wrapper.cache.local", BOOLEAN, JAVA)
            .status(UNSUPPORTED)
            .description("Network launching: copy local files into wrapper.cache too")
            .build();

    public static final KeySpec RESOURCE = yajsw("wrapper.resource.<n>", STRING, JAVA)
            .status(UNSUPPORTED)
            .description("Network launching: file downloaded from the code base before the application starts")
            .build();

    public static final KeySpec WRAPPER_JAR = yajsw("wrapper.wrapperJar", PATH, JAVA)
            .status(IGNORED)
            .description("Full path of the jar the wrapper runs from, when YAJSW is embedded in an application")
            .notes("nyajsw does not use YAJSW's jars")
            .build();

    public static final KeySpec APP_JAR = yajsw("wrapper.appJar", PATH, JAVA)
            .status(IGNORED)
            .description("Full path of the jar the application JVM uses, when YAJSW is embedded in an application")
            .notes("nyajsw does not use YAJSW's jars")
            .build();

    public static final KeySpec GROOVY = yajsw("wrapper.groovy", STRING, JAVA)
            .status(UNSUPPORTED)
            .description("Groovy script run by the wrapper")
            .build();

    // ---- lifecycle ----

    public static final KeySpec CONTROL = yajsw("wrapper.control", ENUM, LIFECYCLE)
            .status(SUPPORTED)
            .description("Coupling between the lifetimes of the wrapper and the application")
            .values(names(ControlMode.class))
            .defaultValue("TIGHT")
            .notes("The exit action STOP keeps the wrapper running only under LOOSE and APPLICATION; a stop of the"
                    + " wrapper leaves the application running under LOOSE and WRAPPER (D20)")
            .build();

    public static final KeySpec ON_EXIT_DEFAULT = yajsw("wrapper.on_exit.default", ENUM, LIFECYCLE)
            .status(SUPPORTED)
            .description("Action when the application exits with a code that has no wrapper.on_exit.<code>")
            .values(EXIT_ACTIONS)
            .defaultValue("SHUTDOWN")
            .notes(STOP_ACTION_NOTE)
            .build();

    public static final KeySpec ON_EXIT_0 = yajsw("wrapper.on_exit.0", ENUM, LIFECYCLE)
            .status(SUPPORTED)
            .description("Action when the application exits with code 0; when absent, wrapper.on_exit.default"
                    + " applies")
            .values(EXIT_ACTIONS)
            .notes(STOP_ACTION_NOTE)
            .build();

    public static final KeySpec ON_EXIT = yajsw("wrapper.on_exit.<code>", ENUM, LIFECYCLE)
            .status(SUPPORTED)
            .description("Action when the application exits with this exit code")
            .values(EXIT_ACTIONS)
            .notes(STOP_ACTION_NOTE)
            .build();

    public static final KeySpec RESTART_DELAY = yajsw("wrapper.restart.delay", DURATION, LIFECYCLE)
            .status(SUPPORTED)
            .description("Pause between the end of one invocation and the next start")
            .defaultValue("5")
            .build();

    public static final KeySpec MAX_FAILED_INVOCATIONS = yajsw("wrapper.max_failed_invocations", INTEGER, LIFECYCLE)
            .status(SUPPORTED)
            .description("Failed invocations in a row after which the wrapper gives up")
            .defaultValue("5")
            .notes("N failed invocations in a row end supervision, so at most N-1 restarts follow quick failures"
                    + " (D20). An invocation fails when it cannot be started, does not become ready within"
                    + " wrapper.startup.timeout, or ends within wrapper.successful_invocation_time; a launch error is retried whatever wrapper.on_exit says."
                    + " Values below 1 count as 1")
            .build();

    public static final KeySpec SUCCESSFUL_INVOCATION_TIME =
            yajsw("wrapper.successful_invocation_time", DURATION, LIFECYCLE)
                    .status(SUPPORTED)
                    .description("Run time after which an invocation counts as successful")
                    .defaultValue("60")
                    .build();

    public static final KeySpec STARTUP_DELAY = yajsw("wrapper.startup.delay", DURATION, LIFECYCLE)
            .status(SUPPORTED)
            .description("Pause before the application is started for the first time")
            .defaultValue("0")
            .build();

    public static final KeySpec STARTUP_TIMEOUT = yajsw("wrapper.startup.timeout", DURATION, LIFECYCLE)
            .status(SUPPORTED)
            .description("Time the application has to become ready")
            .defaultValue("30")
            .notes("0 means no limit, as in the Tanuki format")
            .build();

    public static final KeySpec SHUTDOWN_TIMEOUT = yajsw("wrapper.shutdown.timeout", DURATION, LIFECYCLE)
            .status(SUPPORTED)
            .description("Time the application has to stop gracefully before it is terminated")
            .defaultValue("30")
            .notes("YAJSW documents no default and bounds the stop by this and wrapper.jvm_exit.timeout together."
                    + " In console mode that sum is the time the application has after Ctrl+C, which it receives too")
            .toVerify(DEFAULT)
            .build();

    public static final KeySpec JVM_EXIT_TIMEOUT = yajsw("wrapper.jvm_exit.timeout", DURATION, LIFECYCLE)
            .status(SUPPORTED)
            .description("Time added to wrapper.shutdown.timeout before the application is terminated")
            .notes("When not set it adds nothing")
            .build();

    public static final KeySpec PIDFILE = yajsw("wrapper.pidfile", PATH, LIFECYCLE)
            .status(SUPPORTED)
            .description("File the wrapper writes its process id to")
            .build();

    public static final KeySpec LOCKFILE = yajsw("wrapper.lockfile", PATH, LIFECYCLE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("File locked while the application runs, so that a second instance does not start")
            .build();

    public static final KeySpec JAVA_PIDFILE = yajsw("wrapper.java.pidfile", PATH, LIFECYCLE)
            .status(SUPPORTED)
            .description("File the wrapper writes the application's process id to")
            .build();

    public static final KeySpec PING_INTERVAL = yajsw("wrapper.ping.interval", DURATION, LIFECYCLE)
            .status(PLANNED, "P7")
            .description("Heartbeat interval, mapped onto the agent liveness probe (D16)")
            .defaultValue("5")
            .build();

    public static final KeySpec PING_TIMEOUT = yajsw("wrapper.ping.timeout", DURATION, LIFECYCLE)
            .status(PLANNED, "P7")
            .description("Heartbeat timeout, mapped onto the agent liveness probe (D16)")
            .defaultValue("30")
            .build();

    public static final KeySpec RESTART_RELOAD_CONFIGURATION =
            yajsw("wrapper.restart.reload_configuration", BOOLEAN, LIFECYCLE)
                    .status(UNSUPPORTED, P9_BACKLOG)
                    .description("Reload the configuration file before each restart")
                    .notes("YAJSW default: false")
                    .build();

    public static final KeySpec RESTART_RELOAD_CACHE = yajsw("wrapper.restart.reload_cache", BOOLEAN, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("Network launching: refresh the cached files when the configuration is reloaded")
            .build();

    public static final KeySpec RESTART_DELAY_SCRIPT = yajsw("wrapper.restart.delay.script", STRING, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("Groovy script that computes the restart delay")
            .build();

    public static final KeySpec RESTART_DELAY_SCRIPT_ARGS =
            yajsw("wrapper.restart.delay.script.args", STRING, LIFECYCLE)
                    .status(UNSUPPORTED)
                    .description("Arguments of wrapper.restart.delay.script")
                    .build();

    public static final KeySpec STOP_CONF = yajsw("wrapper.stop.conf", PATH, LIFECYCLE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Configuration of a separate program that stops the application")
            .build();

    public static final KeySpec STOPPER = yajsw("wrapper.stopper", BOOLEAN, LIFECYCLE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Marks a configuration as the stop program named by wrapper.stop.conf")
            .build();

    public static final KeySpec EXIT_ON_MAIN_TERMINATE = yajsw("wrapper.exit_on_main_terminate", STRING, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("In-JVM behaviour when the application's main method returns")
            .notes("YAJSW default: -1, the application keeps running while it has non-daemon threads")
            .build();

    public static final KeySpec EXIT_ON_MAIN_EXCEPTION = yajsw("wrapper.exit_on_main_exception", STRING, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("In-JVM behaviour when the application's main method throws")
            .notes("YAJSW default: 999, the exit code used")
            .build();

    public static final KeySpec EXIT_CODE_STOP = yajsw("wrapper.exit_code.stop", INTEGER, LIFECYCLE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Exit code reported when the application stopped as asked")
            .notes("YAJSW default: 0")
            .build();

    public static final KeySpec EXIT_CODE_KILL = yajsw("wrapper.exit_code.kill", INTEGER, LIFECYCLE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Exit code reported when the application had to be terminated")
            .notes("YAJSW default: 999")
            .build();

    public static final KeySpec EXIT_CODE_FATAL = yajsw("wrapper.exit_code.fatal", INTEGER, LIFECYCLE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Exit code reported when the application cannot start or its main method throws")
            .notes("YAJSW default: 999")
            .build();

    public static final KeySpec SCRIPT = yajsw("wrapper.script.<state>", STRING, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("Groovy script run when the application changes state, with its .args and .timeout")
            .build();

    public static final KeySpec SCRIPT_RELOAD = yajsw("wrapper.script.reload", BOOLEAN, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("Reload changed Groovy scripts before running them")
            .build();

    public static final KeySpec SCRIPT_ENCODING = yajsw("wrapper.script.encoding", STRING, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("Character encoding of the Groovy scripts")
            .build();

    public static final KeySpec CONDITION_SCRIPT = yajsw("wrapper.condition.script", STRING, LIFECYCLE)
            .status(UNSUPPORTED_FATAL)
            .description("Groovy script that decides when the application is started or stopped")
            .notes("Fatal because ignoring it would keep the application running whatever the script decides")
            .build();

    public static final KeySpec CONDITION_SCRIPT_ARGS = yajsw("wrapper.condition.script.args", STRING, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("Arguments of wrapper.condition.script")
            .build();

    public static final KeySpec CONDITION_CYCLE = yajsw("wrapper.condition.cycle", DURATION, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("Interval at which wrapper.condition.script runs")
            .build();

    public static final KeySpec TIMER = yajsw("wrapper.timer.<setting>", STRING, LIFECYCLE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Cron or interval timers that start, stop or restart the application")
            .build();

    public static final KeySpec MONITOR = yajsw("wrapper.monitor.<setting>", STRING, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("Monitors that compare application metrics with a threshold and act on them")
            .build();

    public static final KeySpec FILTER_TRIGGER = yajsw("wrapper.filter.trigger.<n>", STRING, LIFECYCLE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Text in the application output that triggers an action")
            .build();

    public static final KeySpec FILTER_TRIGGER_REGEX = yajsw("wrapper.filter.trigger-regex.<n>", STRING, LIFECYCLE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Regular expression in the application output that triggers an action")
            .build();

    public static final KeySpec FILTER_SCRIPT = yajsw("wrapper.filter.script.<n>", STRING, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("Groovy script run when wrapper.filter.trigger.<n> matches")
            .build();

    public static final KeySpec FILTER_SCRIPT_ARGS = yajsw("wrapper.filter.script.<n>.args", STRING, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("Arguments of wrapper.filter.script.<n>")
            .build();

    public static final KeySpec FILTER_SCRIPT_TIMEOUT = yajsw("wrapper.filter.script.<n>.timeout", DURATION, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("Time one run of wrapper.filter.script.<n> may take")
            .build();

    public static final KeySpec FILTER_SCRIPT_MAX_CONC_INVOC =
            yajsw("wrapper.filter.script.<n>.maxConcInvoc", INTEGER, LIFECYCLE)
                    .status(UNSUPPORTED)
                    .description("Runs of wrapper.filter.script.<n> allowed at the same time")
                    .build();

    public static final KeySpec FILTER_ACTION = yajsw("wrapper.filter.action.<n>", ENUM, LIFECYCLE)
            .status(PLANNED, P9_BACKLOG)
            .description("Action when wrapper.filter.trigger.<n> matches")
            .values(names(FilterAction.class))
            .defaultValue("RESTART")
            .build();

    public static final KeySpec FILTER_MISSING = yajsw("wrapper.filter.missing.<setting>", STRING, LIFECYCLE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Filters that act when expected output stops appearing")
            .build();

    public static final KeySpec FILTER_DEBUG_DEFAULT = yajsw("wrapper.filter.debug.default", BOOLEAN, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("Log the matching of output filters")
            .toVerify(MEANING)
            .build();

    public static final KeySpec STOP_STRATEGY = nyajsw("wrapper.stop.strategy", LIST, LIFECYCLE)
            .status(SUPPORTED)
            .description("Ordered stop strategies tried before the application is terminated")
            .derivedDefault("agent,terminate when wrapper.agent.enabled is true (from P7), otherwise terminate")
            .notes("Names ignore case; unknown ones are skipped with a warning and terminate is always the last"
                    + " step. The strategies before it share wrapper.shutdown.timeout plus wrapper.jvm_exit.timeout;"
                    + " when both are 0 they are skipped. Strategies: implementation plan, section 4.6")
            .build();

    public static final KeySpec STOP_COMMAND = nyajsw("wrapper.stop.command", STRING, LIFECYCLE)
            .status(PLANNED, "P7")
            .description("Command run by the command stop strategy")
            .build();

    // ---- logging ----

    public static final KeySpec LOGFILE = yajsw("wrapper.logfile", PATH, LOGGING)
            .status(SUPPORTED)
            .description("Wrapper log file; may contain the ROLLNUM and YYYYMMDD tokens")
            .derivedDefault("wrapper.log in the directory of the configuration file (D13)")
            .notes("Blank turns the log file off. In the file name, YYYYMMDD is the date the file is opened and"
                    + " ROLLNUM the number of a rolled copy (left out, with a . _ or - next to it, for the current"
                    + " file); without ROLLNUM, rolled copies are named <file>.1, <file>.2, 1 the newest")
            .build();

    public static final KeySpec LOGFILE_FORMAT = yajsw("wrapper.logfile.format", STRING, LOGGING)
            .status(SUPPORTED)
            .description("Format tokens of log file lines")
            .defaultValue("LPTM")
            .notes("Same letters as wrapper.console.format; blank turns the log file off")
            .build();

    public static final KeySpec LOGFILE_LOGLEVEL = yajsw("wrapper.logfile.loglevel", ENUM, LOGGING)
            .status(SUPPORTED)
            .description("Lowest level written to the log file")
            .values(LOG_LEVELS)
            .defaultValue("INFO")
            .build();

    public static final KeySpec LOGFILE_MAXSIZE = yajsw("wrapper.logfile.maxsize", SIZE, LOGGING)
            .status(SUPPORTED)
            .description("Size at which the log file rolls; 0 turns rolling by size off")
            .defaultValue("0")
            .build();

    public static final KeySpec LOGFILE_MAXFILES = yajsw("wrapper.logfile.maxfiles", INTEGER, LOGGING)
            .status(SUPPORTED)
            .description("Number of rolled log files kept, per day under the DATE roll mode")
            .notes("When not set, or 0, every rolled file is kept")
            .build();

    public static final KeySpec LOGFILE_MAXDAYS = yajsw("wrapper.logfile.maxdays", INTEGER, LOGGING)
            .status(SUPPORTED)
            .description("Days of log files kept under the DATE roll mode; -1 keeps all")
            .defaultValue("-1")
            .notes("Files dated more than this many days before the current one are deleted, rolled copies"
                    + " included, when a day's file is opened; 0 keeps the current day only. A system clock that"
                    + " jumps ahead deletes the files of the days it skips")
            .build();

    public static final KeySpec LOGFILE_ROLLMODE = yajsw("wrapper.logfile.rollmode", ENUM, LOGGING)
            .status(SUPPORTED)
            .description("When the log file rolls, besides rolling by size")
            .values(names(RollMode.class))
            .alias("SIZE_OR_JVM", "JVM")
            .alias("SIZE_OR_WRAPPER", "WRAPPER")
            .toVerify(VALUES)
            .notes("Not set or SIZE: by size only. JVM: also at wrapper start and before each restarted JVM; WRAPPER:"
                    + " also at wrapper start; DATE: a file per day of output (wrapper.logfile needs YYYYMMDD),"
                    + " rolled by size within the day; NONE: never. wrapper.logfile.maxsize applies in every mode but"
                    + " NONE, so the Tanuki values SIZE_OR_JVM and SIZE_OR_WRAPPER are accepted as JVM and WRAPPER"
                    + " (D22)")
            .build();

    public static final KeySpec LOGFILE_DESC = yajsw("wrapper.logfile.desc", BOOLEAN, LOGGING)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Roll by renaming only the newest file, which then gets the highest number")
            .build();

    public static final KeySpec LOG_ENCODING = yajsw("wrapper.log.encoding", STRING, LOGGING)
            .status(SUPPORTED)
            .description("Character encoding of the wrapper log file")
            .derivedDefault("the default charset of the wrapper JVM, as in YAJSW (UTF-8 on Java 18 and later)")
            .notes("YAJSW also reads scripts and configuration files with it; nyajsw reads configuration files"
                    + " as wrapper.conf.encoding says")
            .build();

    public static final KeySpec CONSOLE_FORMAT = yajsw("wrapper.console.format", STRING, LOGGING)
            .status(SUPPORTED)
            .description("Format tokens of console lines")
            .defaultValue("PM")
            .notes("One field per letter, in either case, separated by ' | ': L level, P prefix (wrapper or jvm N),"
                    + " D thread, T time (yyyy/MM/dd HH:mm:ss), Z time with milliseconds, M message; spaces are"
                    + " ignored, other characters skipped with a warning. A trailing \\n or \\r\\n chooses the line"
                    + " ending. Blank turns the console output off")
            .build();

    public static final KeySpec CONSOLE_LOGLEVEL = yajsw("wrapper.console.loglevel", ENUM, LOGGING)
            .status(SUPPORTED)
            .description("Lowest level written to the console")
            .values(LOG_LEVELS)
            .defaultValue("INFO")
            .build();

    public static final KeySpec CONSOLE_VISIBLE = yajsw("wrapper.console.visible", BOOLEAN, LOGGING)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Show a console window for the service")
            .build();

    public static final KeySpec CONSOLE_TITLE = yajsw("wrapper.console.title", STRING, LOGGING)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Title of the console window")
            .build();

    public static final KeySpec CONSOLE_MINIMIZED = yajsw("wrapper.console.minimized", BOOLEAN, LOGGING)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Start the visible console window minimized")
            .build();

    public static final KeySpec CONSOLE_PIPESTREAMS = yajsw("wrapper.console.pipestreams", BOOLEAN, LOGGING)
            .status(IGNORED)
            .description("Pipe the application's standard streams to the wrapper instead of copying them")
            .notes("nyajsw always pipes them, as if true")
            .build();

    public static final KeySpec CONSOLE_USE_INTERPOLATED =
            yajsw("wrapper.console.use_interpolated", BOOLEAN, LOGGING)
                    .status(UNSUPPORTED)
                    .description("Whether Java options of the wrapper JVM are passed on to the application")
                    .build();

    public static final KeySpec APP_STATUS_LOG_LINES = yajsw("wrapper.app.status.log.lines", INTEGER, LOGGING)
            .status(UNSUPPORTED)
            .description("Application output lines logged at the STATUS level")
            .build();

    public static final KeySpec DEBUG = yajsw("wrapper.debug", BOOLEAN, LOGGING)
            .status(SUPPORTED)
            .description("Raise the wrapper log level to DEBUG")
            .defaultValue("false")
            .notes("Raises wrapper.console.loglevel and wrapper.logfile.loglevel; wrapper.debug.level chooses which"
                    + " debug messages are written")
            .build();

    // A string, not an integer: a word in it must only warn while wrapper.debug is false (LogConfig).
    public static final KeySpec DEBUG_LEVEL = yajsw("wrapper.debug.level", STRING, LOGGING)
            .status(SUPPORTED)
            .description("Detail of the wrapper.debug output, a whole number from 0 (least) to 3 (all)")
            .defaultValue("3")
            .notes("Read only when wrapper.debug is true. 0: no debug messages; 1: starting and stopping the"
                    + " application; 2: also the steps of supervision (stop steps, readiness checks, pid files);"
                    + " 3: everything. Another value is an error while wrapper.debug is true, a warning otherwise")
            .build();

    public static final KeySpec SYSLOG = yajsw("wrapper.syslog.<setting>", STRING, LOGGING)
            .status(UNSUPPORTED)
            .description("Logging to syslog (facility, ident, loglevel)")
            .build();

    // ---- Windows service ----

    public static final KeySpec NTSERVICE_NAME = yajsw("wrapper.ntservice.name", STRING, SERVICE)
            .status(SUPPORTED)
            .description("Service name")
            .derivedDefault("the simple name of the application main class")
            .toVerify(DEFAULT)
            .build();

    public static final KeySpec NTSERVICE_DISPLAYNAME = yajsw("wrapper.ntservice.displayname", STRING, SERVICE)
            .status(SUPPORTED)
            .description("Display name of the service")
            .build();

    public static final KeySpec NTSERVICE_DESCRIPTION = yajsw("wrapper.ntservice.description", STRING, SERVICE)
            .status(SUPPORTED)
            .description("Description of the service")
            .build();

    public static final KeySpec NTSERVICE_STARTTYPE = yajsw("wrapper.ntservice.starttype", ENUM, SERVICE)
            .status(SUPPORTED)
            .description("Start type of the service")
            .values(names(StartType.class))
            .alias("DELAY_START", "DELAYED_AUTO_START")
            .defaultValue("AUTO_START")
            .build();

    public static final KeySpec NTSERVICE_DEPENDENCY = yajsw("wrapper.ntservice.dependency.<n>", STRING, SERVICE)
            .status(SUPPORTED)
            .description("Service that must run before this one")
            .build();

    public static final KeySpec NTSERVICE_ACCOUNT = yajsw("wrapper.ntservice.account", STRING, SERVICE)
            .status(SUPPORTED)
            .description("Account the service runs as; granted the right to log on as a service at install")
            .notes("When absent the service runs as LocalSystem. The built-in service accounts and virtual"
                    + " accounts (NT SERVICE\\name) are granted nothing; any other account must be a user, and"
                    + " a local one is written .\\name")
            .build();

    public static final KeySpec NTSERVICE_PASSWORD = yajsw("wrapper.ntservice.password", STRING, SERVICE)
            .status(SUPPORTED)
            .secret()
            .description("Password of wrapper.ntservice.account")
            .build();

    public static final KeySpec NTSERVICE_INTERACTIVE = yajsw("wrapper.ntservice.interactive", BOOLEAN, SERVICE)
            .status(IGNORED)
            .description("Let the service interact with the desktop")
            .notes("Obsolete since Windows Vista isolated services in session 0")
            .build();

    public static final KeySpec NTSERVICE_FAILURE_ACTIONS =
            yajsw("wrapper.ntservice.failure_actions.<setting>", STRING, SERVICE)
                    .status(UNSUPPORTED, P9_BACKLOG)
                    .description("SCM recovery actions (reset_period, reboot_msg, command, actions, actions_delay)")
                    .build();

    public static final KeySpec NTSERVICE_ADDITIONAL = yajsw("wrapper.ntservice.additional.<n>", STRING, SERVICE)
            .status(SUPPORTED)
            .description("JVM option for the wrapper JVM, written into the service binary path (D12)")
            .build();

    public static final KeySpec NTSERVICE_JAVA_COMMAND = yajsw("wrapper.ntservice.java.command", PATH, SERVICE)
            .status(SUPPORTED)
            .description("Java executable of the wrapper JVM that runs the service")
            .derivedDefault("javaw from the java.home of the JVM that installs the service (D12)")
            .notes("YAJSW defaults to the application's Java command; nyajsw needs Java 21 here. Give it"
                    + " with its directory (resolved against the configuration file's); a bare name, a missing"
                    + " file or a Java older than 21 (told by its installation's release file) is an error at"
                    + " install, and a Java whose version cannot be told a warning")
            .build();

    public static final KeySpec NTSERVICE_JAVA_CUSTOM_PROC_NAME =
            yajsw("wrapper.ntservice.java.customProcName", STRING, SERVICE)
                    .status(UNSUPPORTED, P9_BACKLOG)
                    .description("Run the service from a renamed copy of the Java executable")
                    .build();

    public static final KeySpec NTSERVICE_AUTOREPORT_STARTUP =
            yajsw("wrapper.ntservice.autoreport.startup", BOOLEAN, SERVICE)
                    .status(UNSUPPORTED)
                    .description("Report the service as running as soon as the application starts")
                    .notes("Readiness probes decide when nyajsw reports RUNNING (wrapper.readiness.type); with"
                            + " false, YAJSW waits for the application to report through YAJSW's API")
                    .build();

    public static final KeySpec NTSERVICE_AUTOREPORT_WAITREADY =
            yajsw("wrapper.ntservice.autoreport.waitready", BOOLEAN, SERVICE)
                    .status(UNSUPPORTED)
                    .description("Show the service as waiting in the tray icon until the application reports")
                    .build();

    public static final KeySpec NTSERVICE_REDUCE_SIGNALS = yajsw("wrapper.ntservice.reduce_signals", BOOLEAN, SERVICE)
            .status(PLANNED, "P6")
            .description("Start the application JVM with -Xrs in service mode, so a user logging off does not"
                    + " stop it")
            .defaultValue("true")
            .build();

    public static final KeySpec NTSERVICE_LOGON_ACTIVE_SESSION =
            yajsw("wrapper.ntservice.logon_active_session", BOOLEAN, SERVICE)
                    .status(UNSUPPORTED)
                    .description("Start the application in the session of the logged-on user")
                    .toVerify(MEANING)
                    .build();

    public static final KeySpec NTSERVICE_DESKTOP = yajsw("wrapper.ntservice.desktop", STRING, SERVICE)
            .status(UNSUPPORTED)
            .description("Desktop the application is started on")
            .toVerify(MEANING)
            .build();

    public static final KeySpec SAVE_INTERPOLATED = yajsw("wrapper.save_interpolated", BOOLEAN, SERVICE)
            .status(IGNORED)
            .description("Store the resolved ${...} values with the installed service")
            .notes("nyajsw resolves them again at every start, as if false (plan section 3.4)")
            .build();

    public static final KeySpec WINDOWS_CLUSTER = yajsw("wrapper.windows.cluster.<setting>", STRING, SERVICE)
            .status(UNSUPPORTED_FATAL, P9_BACKLOG)
            .description("Groovy script run when a Windows failover cluster group moves, to start the application"
                    + " only on the active node")
            .notes("Fatal because ignoring it would start the application on every node")
            .build();

    // ---- readiness, liveness and agent ----

    public static final KeySpec READINESS_TYPE = nyajsw("wrapper.readiness.type", LIST, PROBES)
            .status(PLANNED, "P7")
            .description("Readiness probes that must all pass before the service reports RUNNING")
            .defaultValue("process")
            .notes("Probe types and their settings: implementation plan, section 4.7")
            .build();

    public static final KeySpec READINESS_INTERVAL = nyajsw("wrapper.readiness.interval", DURATION, PROBES)
            .status(PLANNED, "P7")
            .description("Pause between readiness checks")
            .defaultValue("1")
            .build();

    public static final KeySpec READINESS_TCP_HOST = nyajsw("wrapper.readiness.tcp.host", STRING, PROBES)
            .status(PLANNED, "P7")
            .description("Host the tcp readiness probe connects to")
            .defaultValue("127.0.0.1")
            .build();

    public static final KeySpec READINESS_TCP_PORT = nyajsw("wrapper.readiness.tcp.port", INTEGER, PROBES)
            .status(PLANNED, "P7")
            .description("Port the tcp readiness probe connects to")
            .build();

    public static final KeySpec READINESS_HTTP_URL = nyajsw("wrapper.readiness.http.url", STRING, PROBES)
            .status(PLANNED, "P7")
            .description("URL the http readiness probe requests")
            .build();

    public static final KeySpec READINESS_HTTP_STATUS = nyajsw("wrapper.readiness.http.status", STRING, PROBES)
            .status(PLANNED, "P7")
            .description("HTTP status codes the http readiness probe accepts")
            .defaultValue("200-399")
            .build();

    public static final KeySpec READINESS_HTTP_TIMEOUT = nyajsw("wrapper.readiness.http.timeout", DURATION, PROBES)
            .status(PLANNED, "P7")
            .description("Time one http readiness request may take")
            .build();

    public static final KeySpec READINESS_LOG_PATTERN = nyajsw("wrapper.readiness.log.pattern", STRING, PROBES)
            .status(PLANNED, "P7")
            .description("Regular expression the log readiness probe waits for")
            .build();

    public static final KeySpec READINESS_LOG_FILE = nyajsw("wrapper.readiness.log.file", PATH, PROBES)
            .status(PLANNED, "P7")
            .description("File the log readiness probe reads")
            .notes("When absent the probe reads the captured application output")
            .build();

    public static final KeySpec READINESS_FILE_PATH = nyajsw("wrapper.readiness.file.path", PATH, PROBES)
            .status(PLANNED, "P7")
            .description("File whose existence the file readiness probe waits for")
            .build();

    public static final KeySpec READINESS_COMMAND = nyajsw("wrapper.readiness.command", STRING, PROBES)
            .status(PLANNED, "P7")
            .description("Command whose exit code 0 the command readiness probe waits for")
            .build();

    public static final KeySpec READINESS_COMMAND_TIMEOUT =
            nyajsw("wrapper.readiness.command.timeout", DURATION, PROBES)
                    .status(PLANNED, "P7")
                    .description("Time one run of wrapper.readiness.command may take")
                    .build();

    public static final KeySpec READINESS_JMX_URL = nyajsw("wrapper.readiness.jmx.url", STRING, PROBES)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("JMX service URL the jmx readiness probe connects to")
            .build();

    public static final KeySpec READINESS_JMX_ATTRIBUTE = nyajsw("wrapper.readiness.jmx.attribute", STRING, PROBES)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("MBean attribute the jmx readiness probe reads")
            .build();

    public static final KeySpec READINESS_JMX_VALUE = nyajsw("wrapper.readiness.jmx.value", STRING, PROBES)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Attribute value the jmx readiness probe waits for")
            .build();

    public static final KeySpec LIVENESS_TYPE = nyajsw("wrapper.liveness.type", LIST, PROBES)
            .status(PLANNED, "P7")
            .description("Liveness probes checked while the application runs")
            .derivedDefault("agent when wrapper.agent.enabled is true (from P7), otherwise none (D16)")
            .build();

    public static final KeySpec LIVENESS_INTERVAL = nyajsw("wrapper.liveness.interval", DURATION, PROBES)
            .status(PLANNED, "P7")
            .description("Pause between liveness checks")
            .defaultValue("10")
            .build();

    public static final KeySpec LIVENESS_FAILURES = nyajsw("wrapper.liveness.failures", INTEGER, PROBES)
            .status(PLANNED, "P7")
            .description("Consecutive liveness failures before wrapper.liveness.action is taken")
            .defaultValue("3")
            .build();

    public static final KeySpec LIVENESS_ACTION = nyajsw("wrapper.liveness.action", ENUM, PROBES)
            .status(PLANNED, "P7")
            .description("Action when the liveness probes keep failing")
            .values(names(FailureAction.class))
            .defaultValue("RESTART")
            .build();

    public static final KeySpec AGENT_ENABLED = nyajsw("wrapper.agent.enabled", BOOLEAN, PROBES)
            .status(PLANNED, "P7")
            .description("Load the nyajsw agent into the application JVM")
            .defaultValue("true")
            .build();

    public static final KeySpec PORT = yajsw("wrapper.port", INTEGER, PROBES)
            .status(IGNORED)
            .description("Port of the channel between the wrapper and the application")
            .notes("The nyajsw agent listens on a loopback port chosen at launch")
            .build();

    public static final KeySpec PORT_MIN = yajsw("wrapper.port.min", INTEGER, PROBES)
            .status(IGNORED)
            .description("Lowest port tried for the channel between the wrapper and the application")
            .notes("The nyajsw agent listens on a loopback port chosen at launch")
            .build();

    public static final KeySpec PORT_MAX = yajsw("wrapper.port.max", INTEGER, PROBES)
            .status(IGNORED)
            .description("Highest port tried for the channel between the wrapper and the application")
            .notes("The nyajsw agent listens on a loopback port chosen at launch")
            .build();

    // ---- out of scope ----

    public static final KeySpec DAEMON_DIR = yajsw("wrapper.daemon.dir", PATH, OUT_OF_SCOPE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("POSIX daemon script directory")
            .build();

    public static final KeySpec DAEMON_PID_DIR = yajsw("wrapper.daemon.pid.dir", PATH, OUT_OF_SCOPE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("POSIX daemon pid file directory")
            .build();

    public static final KeySpec DAEMON_RUN_LEVEL_DIR = yajsw("wrapper.daemon.run_level_dir", PATH, OUT_OF_SCOPE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("POSIX run-level directory")
            .build();

    public static final KeySpec DAEMON_UPDATE_RC = yajsw("wrapper.daemon.update_rc", STRING, OUT_OF_SCOPE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("POSIX update-rc.d invocation")
            .build();

    public static final KeySpec DAEMON_TEMPLATE = yajsw("wrapper.daemon.template", PATH, OUT_OF_SCOPE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Template of the POSIX daemon script")
            .build();

    public static final KeySpec LAUNCHD_DIR = yajsw("wrapper.launchd.dir", PATH, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("macOS launchd agent directory")
            .build();

    public static final KeySpec PLIST_TEMPLATE = yajsw("wrapper.plist.template", PATH, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("Template of the macOS launchd property list")
            .build();

    public static final KeySpec FORK_HACK = yajsw("wrapper.fork_hack", BOOLEAN, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("POSIX workaround for starting the application process")
            .build();

    public static final KeySpec POSIX_SPAWN = yajsw("wrapper.posix_spawn", BOOLEAN, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("Start the application with posix_spawn")
            .build();

    public static final KeySpec POSIX_VFORK = yajsw("wrapper.posix_vfork", BOOLEAN, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("Start the application with vfork")
            .build();

    public static final KeySpec TRAY = yajsw("wrapper.tray", BOOLEAN, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("System tray icon")
            .build();

    public static final KeySpec TRAY_PORT = yajsw("wrapper.tray.port", INTEGER, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("Port of the system tray icon")
            .toVerify(NAME)
            .build();

    public static final KeySpec TRAY_ICON = yajsw("wrapper.tray.icon", PATH, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("Image of the system tray icon")
            .build();

    public static final KeySpec TRAY_SETTING = yajsw("wrapper.tray.<setting>", STRING, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("Other settings of the system tray icon: menu, dialogs, texts, look and feel")
            .build();

    public static final KeySpec JMX = yajsw("wrapper.jmx", BOOLEAN, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("JMX remote console")
            .build();

    public static final KeySpec JMX_RMI_PORT = yajsw("wrapper.jmx.rmi.port", INTEGER, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("RMI port of the JMX remote console")
            .build();

    public static final KeySpec JMX_RMI_USER = yajsw("wrapper.jmx.rmi.user", STRING, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("User of the JMX remote console")
            .build();

    public static final KeySpec JMX_RMI_PASSWORD = yajsw("wrapper.jmx.rmi.password", STRING, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .secret()
            .description("Password of the JMX remote console")
            .build();

    private static final KeyRegistry REGISTRY = new KeyRegistry(List.of(
            CONF_ENCODING, CONFIG_SCRIPT,

            JAVA_COMMAND, JAVA_COMMAND_JAVAW, JAVA_COMMAND_MIN_VERSION, JAVA_COMMAND_MAX_VERSION,
            JAVA_COMMAND_JRE_ONLY, JAVA_COMMAND_JDK_ONLY, JAVA_COMMAND_PREFER_JRE, JAVA_COMMAND_PREFER_JDK,
            JAVA_CUSTOM_PROC_NAME, JAVA_APP_MAINCLASS, JAVA_APP_JAR, JAVA_MAINCLASS, APP_PARAMETER,
            APP_PARAMETER_STRIPQUOTES, JAVA_CLASSPATH, JAVA_CLASSPATH_USE_ENVIRONMENT, JAVA_CLASSPATH_MISSING_LOG_LEVEL,
            JAVA_LIBRARY_PATH, JAVA_LIBRARY_PATH_APPEND_SYSTEM_PATH, JAVA_ADDITIONAL, JAVA_ADDITIONAL_AUTO_BITS,
            JAVA_ADDITIONAL_AUTO_BITS_PLATFORM, JAVA_ADDITIONAL_STRIPQUOTES, JAVA_INITMEMORY, JAVA_MAXMEMORY,
            JAVA_INITMEMORY_RELATIVE, JAVA_INITIALMEMORY_RELATIVE, JAVA_MAXMEMORY_RELATIVE, APP_ENV, WORKING_DIR,
            TMP_PATH, CLEANUP_TMP, IMAGE, PRIORITY, AFFINITY, APP_ACCOUNT, APP_PASSWORD, JAVA_MONITOR,
            JAVA_DEBUG_PORT, JAVA_JMX, REGISTRY_JAVA_HOME, APP_PRE_SCRIPT, APP_PRE_MAIN_SCRIPT, CODEBASE, CACHE,
            CACHE_LOCAL, RESOURCE, WRAPPER_JAR, APP_JAR, GROOVY,

            CONTROL, ON_EXIT_DEFAULT, ON_EXIT_0, ON_EXIT, RESTART_DELAY, MAX_FAILED_INVOCATIONS,
            SUCCESSFUL_INVOCATION_TIME, STARTUP_DELAY, STARTUP_TIMEOUT, SHUTDOWN_TIMEOUT, JVM_EXIT_TIMEOUT, PIDFILE,
            LOCKFILE, JAVA_PIDFILE, PING_INTERVAL, PING_TIMEOUT, RESTART_RELOAD_CONFIGURATION, RESTART_RELOAD_CACHE,
            RESTART_DELAY_SCRIPT,
            RESTART_DELAY_SCRIPT_ARGS, STOP_CONF, STOPPER, EXIT_ON_MAIN_TERMINATE, EXIT_ON_MAIN_EXCEPTION,
            EXIT_CODE_STOP, EXIT_CODE_KILL, EXIT_CODE_FATAL, SCRIPT, SCRIPT_RELOAD, SCRIPT_ENCODING, CONDITION_SCRIPT,
            CONDITION_SCRIPT_ARGS, CONDITION_CYCLE, TIMER, MONITOR, FILTER_TRIGGER, FILTER_TRIGGER_REGEX,
            FILTER_SCRIPT, FILTER_SCRIPT_ARGS, FILTER_SCRIPT_TIMEOUT, FILTER_SCRIPT_MAX_CONC_INVOC, FILTER_ACTION,
            FILTER_MISSING, FILTER_DEBUG_DEFAULT, STOP_STRATEGY, STOP_COMMAND,

            LOGFILE, LOGFILE_FORMAT, LOGFILE_LOGLEVEL, LOGFILE_MAXSIZE, LOGFILE_MAXFILES, LOGFILE_MAXDAYS,
            LOGFILE_ROLLMODE, LOGFILE_DESC, LOG_ENCODING, CONSOLE_FORMAT, CONSOLE_LOGLEVEL, CONSOLE_VISIBLE,
            CONSOLE_TITLE, CONSOLE_MINIMIZED, CONSOLE_PIPESTREAMS, CONSOLE_USE_INTERPOLATED, APP_STATUS_LOG_LINES,
            DEBUG, DEBUG_LEVEL, SYSLOG,

            NTSERVICE_NAME, NTSERVICE_DISPLAYNAME, NTSERVICE_DESCRIPTION, NTSERVICE_STARTTYPE, NTSERVICE_DEPENDENCY,
            NTSERVICE_ACCOUNT, NTSERVICE_PASSWORD, NTSERVICE_INTERACTIVE, NTSERVICE_FAILURE_ACTIONS,
            NTSERVICE_ADDITIONAL, NTSERVICE_JAVA_COMMAND, NTSERVICE_JAVA_CUSTOM_PROC_NAME,
            NTSERVICE_AUTOREPORT_STARTUP, NTSERVICE_AUTOREPORT_WAITREADY, NTSERVICE_REDUCE_SIGNALS,
            NTSERVICE_LOGON_ACTIVE_SESSION, NTSERVICE_DESKTOP, SAVE_INTERPOLATED, WINDOWS_CLUSTER,

            READINESS_TYPE, READINESS_INTERVAL, READINESS_TCP_HOST, READINESS_TCP_PORT, READINESS_HTTP_URL,
            READINESS_HTTP_STATUS, READINESS_HTTP_TIMEOUT, READINESS_LOG_PATTERN, READINESS_LOG_FILE,
            READINESS_FILE_PATH, READINESS_COMMAND, READINESS_COMMAND_TIMEOUT, READINESS_JMX_URL,
            READINESS_JMX_ATTRIBUTE, READINESS_JMX_VALUE, LIVENESS_TYPE, LIVENESS_INTERVAL, LIVENESS_FAILURES,
            LIVENESS_ACTION, AGENT_ENABLED, PORT, PORT_MIN, PORT_MAX,

            DAEMON_DIR, DAEMON_PID_DIR, DAEMON_RUN_LEVEL_DIR, DAEMON_UPDATE_RC, DAEMON_TEMPLATE, LAUNCHD_DIR,
            PLIST_TEMPLATE, FORK_HACK, POSIX_SPAWN, POSIX_VFORK, TRAY, TRAY_PORT, TRAY_ICON, TRAY_SETTING, JMX,
            JMX_RMI_PORT, JMX_RMI_USER, JMX_RMI_PASSWORD));

    private ConfigKeys() {
    }

    /** The configuration values of an enumeration key, declared once by its Java enum. */
    private static List<String> names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
    }

    /** The registry of every key declared above, in documentation order. */
    public static KeyRegistry registry() {
        return REGISTRY;
    }
}
