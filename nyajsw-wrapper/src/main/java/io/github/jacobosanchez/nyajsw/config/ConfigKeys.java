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
 * <p>Statuses and phases are seeded from the plan's initial compatibility matrix (§7).
 * {@link KeySpec#unverified()} marks YAJSW names, defaults and values that still have to be confirmed
 * against the YAJSW reference (risk R11) before the key is implemented. Keys without a declared default get one, when they need
 * it, in the phase that implements them.
 *
 * <p>To add a key: declare a constant here and add it to {@link #REGISTRY} in the same category
 * block. A test fails when a constant is missing from the registry.
 */
public final class ConfigKeys {

    private static final String P9_BACKLOG = "P9";
    private static final List<String> LOG_LEVELS = names(LogLevel.class);
    private static final List<String> EXIT_ACTIONS = names(ExitAction.class);

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

    // ---- Java process ----

    public static final KeySpec JAVA_COMMAND = yajsw("wrapper.java.command", PATH, JAVA)
            .status(PLANNED, "P2")
            .description("Java executable that runs the application")
            .derivedDefault("java from the wrapper JVM's java.home, or javaw when wrapper.java.command.javaw is true")
            .build();

    public static final KeySpec JAVA_COMMAND_JAVAW = yajsw("wrapper.java.command.javaw", BOOLEAN, JAVA)
            .status(PLANNED, "P2")
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
            .status(PLANNED, "P2")
            .description("Main class of the application")
            .build();

    public static final KeySpec JAVA_APP_JAR = yajsw("wrapper.java.app.jar", PATH, JAVA)
            .status(PLANNED, "P2")
            .description("Executable jar of the application, instead of a main class")
            .build();

    public static final KeySpec JAVA_MAINCLASS = yajsw("wrapper.java.mainclass", STRING, JAVA)
            .status(IGNORED)
            .description("YAJSW's in-JVM bootstrap class")
            .notes("nyajsw starts the application main class directly; the optional agent replaces the bootstrap")
            .build();

    public static final KeySpec APP_PARAMETER = yajsw("wrapper.app.parameter.<n>", STRING, JAVA)
            .status(PLANNED, "P2")
            .description("Program argument passed to the application")
            .build();

    public static final KeySpec JAVA_CLASSPATH = yajsw("wrapper.java.classpath.<n>", PATH, JAVA)
            .status(PLANNED, "P2")
            .description("Classpath entry of the application; * and ** wildcards allowed")
            .build();

    public static final KeySpec JAVA_LIBRARY_PATH = yajsw("wrapper.java.library.path.<n>", PATH, JAVA)
            .status(PLANNED, "P2")
            .description("Entry of the application's java.library.path")
            .build();

    public static final KeySpec JAVA_ADDITIONAL = yajsw("wrapper.java.additional.<n>", STRING, JAVA)
            .status(PLANNED, "P2")
            .description("JVM option or -D system property of the application")
            .build();

    public static final KeySpec JAVA_INITMEMORY = yajsw("wrapper.java.initmemory", INTEGER, JAVA)
            .status(PLANNED, "P2")
            .description("Initial heap of the application in MB (-Xms)")
            .build();

    public static final KeySpec JAVA_MAXMEMORY = yajsw("wrapper.java.maxmemory", INTEGER, JAVA)
            .status(PLANNED, "P2")
            .description("Maximum heap of the application in MB (-Xmx)")
            .build();

    public static final KeySpec JAVA_INITMEMORY_RELATIVE = yajsw("wrapper.java.initmemory.relative", INTEGER, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Initial heap as a percentage of physical memory")
            .build();

    public static final KeySpec JAVA_MAXMEMORY_RELATIVE = yajsw("wrapper.java.maxmemory.relative", INTEGER, JAVA)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Maximum heap as a percentage of physical memory")
            .build();

    public static final KeySpec APP_ENV = yajsw("wrapper.app.env.<variable>", STRING, JAVA)
            .status(PLANNED, "P2")
            .description("Environment variable set for the application")
            .build();

    public static final KeySpec WORKING_DIR = yajsw("wrapper.working.dir", PATH, JAVA)
            .status(PLANNED, "P2")
            .description("Working directory of the application")
            .derivedDefault("the directory of the configuration file (D13)")
            .toVerify(DEFAULT)
            .build();

    public static final KeySpec TMP_PATH = yajsw("wrapper.tmp.path", PATH, JAVA)
            .status(PLANNED, "P2")
            .description("Temporary directory, also used as jna.tmpdir")
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
            .description("Deadlock, heap and garbage-collection monitoring")
            .toVerify(NAME)
            .build();

    public static final KeySpec GROOVY = yajsw("wrapper.groovy", STRING, JAVA)
            .status(UNSUPPORTED)
            .description("Groovy script run by the wrapper")
            .build();

    // ---- lifecycle ----

    public static final KeySpec CONTROL = yajsw("wrapper.control", ENUM, LIFECYCLE)
            .status(PLANNED, "P3")
            .description("Coupling between the lifetimes of the wrapper and the application")
            .values(names(ControlMode.class))
            .defaultValue("TIGHT")
            .toVerify(DEFAULT)
            .build();

    public static final KeySpec ON_EXIT_DEFAULT = yajsw("wrapper.on_exit.default", ENUM, LIFECYCLE)
            .status(PLANNED, "P3")
            .description("Action when the application exits with a code that has no wrapper.on_exit.<code>")
            .values(EXIT_ACTIONS)
            .defaultValue("RESTART")
            .toVerify(DEFAULT)
            .build();

    public static final KeySpec ON_EXIT_0 = yajsw("wrapper.on_exit.0", ENUM, LIFECYCLE)
            .status(PLANNED, "P3")
            .description("Action when the application exits with code 0")
            .values(EXIT_ACTIONS)
            .defaultValue("SHUTDOWN")
            .toVerify(DEFAULT)
            .build();

    public static final KeySpec ON_EXIT = yajsw("wrapper.on_exit.<code>", ENUM, LIFECYCLE)
            .status(PLANNED, "P3")
            .description("Action when the application exits with this exit code")
            .values(EXIT_ACTIONS)
            .build();

    public static final KeySpec RESTART_DELAY = yajsw("wrapper.restart.delay", DURATION, LIFECYCLE)
            .status(PLANNED, "P3")
            .description("Pause between the end of one invocation and the next start")
            .toVerify(DEFAULT)
            .build();

    public static final KeySpec MAX_FAILED_INVOCATIONS = yajsw("wrapper.max_failed_invocations", INTEGER, LIFECYCLE)
            .status(PLANNED, "P3")
            .description("Failed invocations in a row after which the wrapper gives up")
            .defaultValue("5")
            .build();

    public static final KeySpec SUCCESSFUL_INVOCATION_TIME =
            yajsw("wrapper.successful_invocation_time", DURATION, LIFECYCLE)
                    .status(PLANNED, "P3")
                    .description("Run time after which an invocation counts as successful")
                    .defaultValue("300")
                    .build();

    public static final KeySpec STARTUP_TIMEOUT = yajsw("wrapper.startup.timeout", DURATION, LIFECYCLE)
            .status(PLANNED, "P3")
            .description("Time the application has to become ready")
            .defaultValue("30")
            .build();

    public static final KeySpec SHUTDOWN_TIMEOUT = yajsw("wrapper.shutdown.timeout", DURATION, LIFECYCLE)
            .status(PLANNED, "P3")
            .description("Time the application has to stop gracefully before it is terminated")
            .defaultValue("30")
            .build();

    public static final KeySpec PIDFILE = yajsw("wrapper.pidfile", PATH, LIFECYCLE)
            .status(PLANNED, "P3")
            .description("File the wrapper writes its process id to")
            .build();

    public static final KeySpec JAVA_PIDFILE = yajsw("wrapper.java.pidfile", PATH, LIFECYCLE)
            .status(PLANNED, "P3")
            .description("File the wrapper writes the application's process id to")
            .build();

    public static final KeySpec PING_INTERVAL = yajsw("wrapper.ping.interval", DURATION, LIFECYCLE)
            .status(PLANNED, "P7")
            .description("Heartbeat interval, mapped onto the agent liveness probe (D16)")
            .toVerify(NAME)
            .build();

    public static final KeySpec PING_TIMEOUT = yajsw("wrapper.ping.timeout", DURATION, LIFECYCLE)
            .status(PLANNED, "P7")
            .description("Heartbeat timeout, mapped onto the agent liveness probe (D16)")
            .build();

    public static final KeySpec RESTART_RELOAD_CONFIGURATION =
            yajsw("wrapper.restart.reload_configuration", BOOLEAN, LIFECYCLE)
                    .status(UNSUPPORTED, P9_BACKLOG)
                    .description("Reload the configuration file before each restart")
                    .toVerify(NAME)
                    .build();

    public static final KeySpec STOP_CONF = yajsw("wrapper.stop.conf", PATH, LIFECYCLE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Configuration of a separate program that stops the application")
            .build();

    public static final KeySpec EXIT_ON_MAIN_TERMINATE = yajsw("wrapper.exit_on_main_terminate", STRING, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("In-JVM behaviour when the application's main method returns")
            .toVerify(NAME)
            .build();

    public static final KeySpec EXIT_ON_MAIN_EXCEPTION = yajsw("wrapper.exit_on_main_exception", STRING, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("In-JVM behaviour when the application's main method throws")
            .toVerify(NAME)
            .build();

    public static final KeySpec FILTER_TRIGGER = yajsw("wrapper.filter.trigger.<n>", STRING, LIFECYCLE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Text in the application output that triggers an action")
            .build();

    public static final KeySpec FILTER_SCRIPT = yajsw("wrapper.filter.script.<n>", STRING, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("Groovy script run when wrapper.filter.trigger.<n> matches")
            .build();

    public static final KeySpec FILTER_SCRIPT_ARGS = yajsw("wrapper.filter.script.<n>.args", STRING, LIFECYCLE)
            .status(UNSUPPORTED)
            .description("Arguments of wrapper.filter.script.<n>")
            .build();

    public static final KeySpec FILTER_ACTION = nyajsw("wrapper.filter.action.<n>", ENUM, LIFECYCLE)
            .status(PLANNED, P9_BACKLOG)
            .description("Action when wrapper.filter.trigger.<n> matches")
            .values(names(FailureAction.class))
            .build();

    public static final KeySpec STOP_STRATEGY = nyajsw("wrapper.stop.strategy", LIST, LIFECYCLE)
            .status(PLANNED, "P3")
            .description("Ordered stop strategies tried before the application is terminated")
            .derivedDefault("agent,terminate when wrapper.agent.enabled is true (from P7), otherwise terminate")
            .notes("Strategies and their order: implementation plan, section 4.6")
            .build();

    public static final KeySpec STOP_COMMAND = nyajsw("wrapper.stop.command", STRING, LIFECYCLE)
            .status(PLANNED, "P7")
            .description("Command run by the command stop strategy")
            .build();

    // ---- logging ----

    public static final KeySpec LOGFILE = yajsw("wrapper.logfile", PATH, LOGGING)
            .status(PLANNED, "P4")
            .description("Wrapper log file; may contain the ROLLNUM and YYYYMMDD tokens")
            .build();

    public static final KeySpec LOGFILE_FORMAT = yajsw("wrapper.logfile.format", STRING, LOGGING)
            .status(PLANNED, "P4")
            .description("Format tokens of log file lines")
            .build();

    public static final KeySpec LOGFILE_LOGLEVEL = yajsw("wrapper.logfile.loglevel", ENUM, LOGGING)
            .status(PLANNED, "P4")
            .description("Lowest level written to the log file")
            .values(LOG_LEVELS)
            .toVerify(VALUES)
            .build();

    public static final KeySpec LOGFILE_MAXSIZE = yajsw("wrapper.logfile.maxsize", SIZE, LOGGING)
            .status(PLANNED, "P4")
            .description("Size at which the log file rolls")
            .build();

    public static final KeySpec LOGFILE_MAXFILES = yajsw("wrapper.logfile.maxfiles", INTEGER, LOGGING)
            .status(PLANNED, "P4")
            .description("Number of rolled log files kept")
            .build();

    public static final KeySpec LOGFILE_ROLLMODE = yajsw("wrapper.logfile.rollmode", ENUM, LOGGING)
            .status(PLANNED, "P4")
            .description("When the log file rolls")
            .values(names(RollMode.class))
            .toVerify(VALUES)
            .build();

    public static final KeySpec CONSOLE_FORMAT = yajsw("wrapper.console.format", STRING, LOGGING)
            .status(PLANNED, "P4")
            .description("Format tokens of console lines")
            .build();

    public static final KeySpec CONSOLE_LOGLEVEL = yajsw("wrapper.console.loglevel", ENUM, LOGGING)
            .status(PLANNED, "P4")
            .description("Lowest level written to the console")
            .values(LOG_LEVELS)
            .toVerify(VALUES)
            .build();

    public static final KeySpec CONSOLE_VISIBLE = yajsw("wrapper.console.visible", BOOLEAN, LOGGING)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Show a console window for the service")
            .build();

    public static final KeySpec CONSOLE_TITLE = yajsw("wrapper.console.title", STRING, LOGGING)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("Title of the console window")
            .build();

    public static final KeySpec CONSOLE_PIPESTREAMS = yajsw("wrapper.console.pipestreams", BOOLEAN, LOGGING)
            .status(UNSUPPORTED)
            .description("How the application's output streams are piped")
            .toVerify(NAME)
            .build();

    public static final KeySpec DEBUG = yajsw("wrapper.debug", BOOLEAN, LOGGING)
            .status(PLANNED, "P4")
            .description("Raise the wrapper log level to DEBUG")
            .defaultValue("false")
            .build();

    public static final KeySpec JAVA_DEBUG = yajsw("wrapper.java.debug", BOOLEAN, LOGGING)
            .status(PLANNED, "P4")
            .description("Verbose logging about the application JVM")
            .defaultValue("false")
            .toVerify(MEANING)
            .build();

    public static final KeySpec SYSLOG = yajsw("wrapper.syslog.<setting>", STRING, LOGGING)
            .status(UNSUPPORTED)
            .description("Logging to syslog")
            .toVerify(NAME)
            .build();

    // ---- Windows service ----

    public static final KeySpec NTSERVICE_NAME = yajsw("wrapper.ntservice.name", STRING, SERVICE)
            .status(PLANNED, "P5")
            .description("Service name")
            .derivedDefault("the simple name of the application main class")
            .toVerify(DEFAULT)
            .build();

    public static final KeySpec NTSERVICE_DISPLAYNAME = yajsw("wrapper.ntservice.displayname", STRING, SERVICE)
            .status(PLANNED, "P5")
            .description("Display name of the service")
            .build();

    public static final KeySpec NTSERVICE_DESCRIPTION = yajsw("wrapper.ntservice.description", STRING, SERVICE)
            .status(PLANNED, "P5")
            .description("Description of the service")
            .build();

    public static final KeySpec NTSERVICE_STARTTYPE = yajsw("wrapper.ntservice.starttype", ENUM, SERVICE)
            .status(PLANNED, "P5")
            .description("Start type of the service")
            .values(names(StartType.class))
            .alias("DELAY_START", "DELAYED_AUTO_START")
            .defaultValue("AUTO_START")
            .build();

    public static final KeySpec NTSERVICE_DEPENDENCY = yajsw("wrapper.ntservice.dependency.<n>", STRING, SERVICE)
            .status(PLANNED, "P5")
            .description("Service that must run before this one")
            .build();

    public static final KeySpec NTSERVICE_ACCOUNT = yajsw("wrapper.ntservice.account", STRING, SERVICE)
            .status(PLANNED, "P5")
            .description("Account the service runs as; granted the right to log on as a service at install")
            .notes("When absent the service runs as LocalSystem")
            .build();

    public static final KeySpec NTSERVICE_PASSWORD = yajsw("wrapper.ntservice.password", STRING, SERVICE)
            .status(PLANNED, "P5")
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
                    .description("SCM recovery actions")
                    .toVerify(NAME)
                    .build();

    public static final KeySpec NTSERVICE_JAVA_ADDITIONAL =
            nyajsw("wrapper.ntservice.java.additional.<n>", STRING, SERVICE)
                    .status(PLANNED, "P5")
                    .description("JVM option for the wrapper JVM, written into the service binary path (D12)")
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

    public static final KeySpec UPDATE_RC = yajsw("wrapper.update_rc", STRING, OUT_OF_SCOPE)
            .status(UNSUPPORTED, P9_BACKLOG)
            .description("POSIX update-rc.d invocation")
            .build();

    public static final KeySpec TRAY = yajsw("wrapper.tray", BOOLEAN, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("System tray icon")
            .build();

    public static final KeySpec TRAY_PORT = yajsw("wrapper.tray.port", INTEGER, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("Port of the system tray icon")
            .build();

    public static final KeySpec TRAY_ICON = yajsw("wrapper.tray.icon", PATH, OUT_OF_SCOPE)
            .status(UNSUPPORTED)
            .description("Image of the system tray icon")
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
            CONF_ENCODING,

            JAVA_COMMAND, JAVA_COMMAND_JAVAW, JAVA_COMMAND_MIN_VERSION, JAVA_COMMAND_MAX_VERSION,
            JAVA_COMMAND_JRE_ONLY, JAVA_COMMAND_JDK_ONLY, JAVA_COMMAND_PREFER_JRE, JAVA_COMMAND_PREFER_JDK,
            JAVA_CUSTOM_PROC_NAME, JAVA_APP_MAINCLASS, JAVA_APP_JAR, JAVA_MAINCLASS, APP_PARAMETER,
            JAVA_CLASSPATH, JAVA_LIBRARY_PATH, JAVA_ADDITIONAL, JAVA_INITMEMORY, JAVA_MAXMEMORY,
            JAVA_INITMEMORY_RELATIVE, JAVA_MAXMEMORY_RELATIVE, APP_ENV, WORKING_DIR, TMP_PATH, IMAGE, PRIORITY,
            AFFINITY, APP_ACCOUNT, APP_PASSWORD, JAVA_MONITOR, GROOVY,

            CONTROL, ON_EXIT_DEFAULT, ON_EXIT_0, ON_EXIT, RESTART_DELAY, MAX_FAILED_INVOCATIONS, SUCCESSFUL_INVOCATION_TIME,
            STARTUP_TIMEOUT, SHUTDOWN_TIMEOUT, PIDFILE, JAVA_PIDFILE, PING_INTERVAL, PING_TIMEOUT,
            RESTART_RELOAD_CONFIGURATION, STOP_CONF, EXIT_ON_MAIN_TERMINATE, EXIT_ON_MAIN_EXCEPTION, FILTER_TRIGGER,
            FILTER_SCRIPT, FILTER_SCRIPT_ARGS, FILTER_ACTION, STOP_STRATEGY, STOP_COMMAND,

            LOGFILE, LOGFILE_FORMAT, LOGFILE_LOGLEVEL, LOGFILE_MAXSIZE, LOGFILE_MAXFILES, LOGFILE_ROLLMODE,
            CONSOLE_FORMAT, CONSOLE_LOGLEVEL, CONSOLE_VISIBLE, CONSOLE_TITLE, CONSOLE_PIPESTREAMS, DEBUG, JAVA_DEBUG,
            SYSLOG,

            NTSERVICE_NAME, NTSERVICE_DISPLAYNAME, NTSERVICE_DESCRIPTION, NTSERVICE_STARTTYPE, NTSERVICE_DEPENDENCY,
            NTSERVICE_ACCOUNT, NTSERVICE_PASSWORD, NTSERVICE_INTERACTIVE, NTSERVICE_FAILURE_ACTIONS,
            NTSERVICE_JAVA_ADDITIONAL,

            READINESS_TYPE, READINESS_INTERVAL, READINESS_TCP_HOST, READINESS_TCP_PORT, READINESS_HTTP_URL,
            READINESS_HTTP_STATUS, READINESS_HTTP_TIMEOUT, READINESS_LOG_PATTERN, READINESS_LOG_FILE,
            READINESS_FILE_PATH, READINESS_COMMAND, READINESS_COMMAND_TIMEOUT, READINESS_JMX_URL,
            READINESS_JMX_ATTRIBUTE, READINESS_JMX_VALUE, LIVENESS_TYPE, LIVENESS_INTERVAL, LIVENESS_FAILURES,
            LIVENESS_ACTION, AGENT_ENABLED,

            DAEMON_DIR, DAEMON_PID_DIR, DAEMON_RUN_LEVEL_DIR, UPDATE_RC, TRAY, TRAY_PORT, TRAY_ICON, JMX,
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
