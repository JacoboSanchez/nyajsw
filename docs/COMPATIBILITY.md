# nyajsw configuration compatibility

<!-- Generated from the key registry (ConfigKeys). Do not edit by hand; regenerate with:
     mvn -q -pl nyajsw-wrapper -am test -Dtest=CompatibilityDocTest -Dsurefire.failIfNoSpecifiedTests=false -Dnyajsw.docs.update=true -->

Every configuration key nyajsw knows, with how far it is supported. Keys come from YAJSW's
`wrapper.conf` unless marked as a nyajsw extension. A file may contain any of them: unknown
`wrapper.*` keys and keys that are planned, ignored or unsupported produce a warning naming
the key. Only two things stop the wrapper: a key marked *fatal* set to a non-empty value,
because ignoring it would silently change behaviour, and a value of a supported key that does
not fit its type. Keys outside `wrapper.*` are plain variables for `${...}`.

Status: **supported** takes effect · **planned** not implemented yet, no effect · **ignored**
accepted on purpose, no effect · **unsupported** no effect · **unsupported, fatal when
present** stops the wrapper when set. A phase in brackets refers to the
[implementation plan](IMPLEMENTATION_PLAN.md): the phase that implements a planned key, or
for an unsupported key the backlog (P9) where it may be added later. *Verify* marks parts of
a YAJSW declaration still to be confirmed against YAJSW itself.

| Status | Keys |
|---|---|
| supported | 30 |
| planned | 42 |
| ignored | 9 |
| unsupported | 92 |
| unsupported, fatal when present | 5 |
| *of which nyajsw extensions* | 22 |
| **total** | 178 |

## Configuration file

| Key | Meaning | Type | Default | Status | Notes |
|---|---|---|---|---|---|
| `wrapper.conf.encoding` | Character encoding of the configuration file and its includes | text | UTF-8, with a windows-1252 fallback and a warning for files that are not valid UTF-8 (D7) | **supported** | A Java charset name, without variables. Set by a system property (required for UTF-16) or in the file itself. Decoding is strict: a byte that does not fit is an error at its line. YAJSW defaults to the platform charset instead |
| `wrapper.config.script.<name>` | Groovy script that transforms configuration values, such as a password decryptor | text | — | **unsupported, fatal when present** | Fatal because ignoring it would pass on values that were meant to be transformed first |

## Java process

| Key | Meaning | Type | Default | Status | Notes |
|---|---|---|---|---|---|
| `wrapper.java.command` | Java executable that runs the application | path | java from the wrapper JVM's java.home, or javaw when wrapper.java.command.javaw is true | **supported** |  |
| `wrapper.java.command.javaw` | Use javaw instead of java for the default Java executable | boolean | `false` | **supported** | *Verify:* default |
| `wrapper.java.command.minVersion` | Lowest Java version accepted when searching for a JVM | text | — | **unsupported** (P9) |  |
| `wrapper.java.command.maxVersion` | Highest Java version accepted when searching for a JVM | text | — | **unsupported** (P9) |  |
| `wrapper.java.command.jreOnly` | Accept only a JRE when searching for a JVM | boolean | — | **unsupported** (P9) |  |
| `wrapper.java.command.jdkOnly` | Accept only a JDK when searching for a JVM | boolean | — | **unsupported** (P9) |  |
| `wrapper.java.command.preferJre` | Prefer a JRE when searching for a JVM | boolean | — | **unsupported** (P9) |  |
| `wrapper.java.command.preferJdk` | Prefer a JDK when searching for a JVM | boolean | — | **unsupported** (P9) |  |
| `wrapper.java.customProcName` | Run the application from a renamed copy of the Java executable | text | — | **unsupported** (P9) |  |
| `wrapper.java.app.mainclass` | Main class of the application | text | — | **supported** |  |
| `wrapper.java.app.jar` | Executable jar of the application, instead of a main class | path | — | **supported** |  |
| `wrapper.java.mainclass` | YAJSW's in-JVM bootstrap class | text | — | **ignored** | nyajsw starts the application main class directly; the optional agent replaces the bootstrap |
| `wrapper.app.parameter.<n>` | Program argument passed to the application | text | — | **supported** |  |
| `wrapper.app.parameter.<n>.stripquotes` | Tanuki: remove quotes from wrapper.app.parameter.&lt;n&gt; | boolean | — | **unsupported** | Declared so that it is not read as a program argument |
| `wrapper.java.classpath.<n>` | Classpath entry of the application; * and ? wildcards as in YAJSW | path | — | **supported** | A whole ** segment matches any depth, a nyajsw extension; matches are sorted by path |
| `wrapper.java.classpath.use_environment` | Tanuki: append the CLASSPATH environment variable to the classpath | boolean | — | **unsupported** | Declared so that it is not read as a classpath entry |
| `wrapper.java.classpath.missing_log_level` | Tanuki: log level used when a classpath entry does not exist | text | — | **unsupported** | Declared so that it is not read as a classpath entry |
| `wrapper.java.library.path.<n>` | Entry of the application's java.library.path | path | — | **supported** | May list several paths separated by ;, as when it copies ${path}; an invalid one in such a list is skipped with a warning |
| `wrapper.java.library.path.append_system_path` | Tanuki: append the system PATH to java.library.path | boolean | — | **unsupported** | Declared so that it is not read as a library path entry |
| `wrapper.java.additional.<n>` | JVM option or -D system property of the application | text | — | **supported** |  |
| `wrapper.java.additional.auto_bits` | Tanuki: add -d32 or -d64 to the JVM options to match the wrapper | boolean | — | **unsupported** | Declared so that it is not read as a JVM option |
| `wrapper.java.additional.auto_bits.<platform>` | Tanuki: wrapper.java.additional.auto_bits for one platform | boolean | — | **unsupported** | Declared so that it is not read as a JVM option |
| `wrapper.java.additional.<n>.stripquotes` | Tanuki: remove quotes from wrapper.java.additional.&lt;n&gt; | boolean | — | **unsupported** | Declared so that it is not read as a JVM option |
| `wrapper.java.initmemory` | Initial heap of the application in MB (-Xms) | integer | — | **supported** |  |
| `wrapper.java.maxmemory` | Maximum heap of the application in MB (-Xmx) | integer | — | **supported** |  |
| `wrapper.java.initmemory.relative` | Initial heap as a percentage of physical memory | integer | — | **unsupported** (P9) |  |
| `wrapper.java.initialmemory.relative` | Initial heap as a percentage of physical memory | integer | — | **unsupported** (P9) | The spelling of the YAJSW reference; wrapper.java.initmemory.relative is the other one |
| `wrapper.java.maxmemory.relative` | Maximum heap as a percentage of physical memory | integer | — | **unsupported** (P9) |  |
| `wrapper.app.env.<variable>` | Environment variable set for the application | text | — | **supported** | An empty value removes the inherited variable; on Windows every spelling of the name is replaced |
| `wrapper.working.dir` | Working directory of the application | path | the directory of the configuration file (D13) | **supported** | *Verify:* default |
| `wrapper.tmp.path` | Temporary directory of the wrapper, also used as jna.tmpdir | path | — | **planned** (P5) | Not passed to the application<br>*Verify:* default |
| `wrapper.cleanup_tmp` | Delete the wrapper's leftover output-buffer files from the temporary directory at start | boolean | — | **unsupported** | YAJSW default: true |
| `wrapper.image` | Non-Java executable to run instead of a JVM | path | — | **planned** (P2) | Optional plan item P2.6 |
| `wrapper.priority` | Process priority of the application | text | — | **unsupported** (P9) |  |
| `wrapper.affinity` | CPU affinity of the application | text | — | **unsupported** (P9) |  |
| `wrapper.app.account` | Run the application as another user | text | — | **unsupported, fatal when present** (P9) | Fatal because ignoring it would run the application under the wrong account |
| `wrapper.app.password` | Password of wrapper.app.account | text | — | **unsupported, fatal when present** (P9) | Secret: never logged or shown. |
| `wrapper.java.monitor.<setting>` | Deadlock, heap and garbage-collection monitoring (deadlock, heap, gc, with .interval, .restart, heap.threshold.percent and gc.threshold) | text | — | **unsupported** (P9) |  |
| `wrapper.java.debug.port` | Debug port of the application; adds the JDWP options and lifts the wrapper's timeouts | integer | — | **unsupported** (P9) |  |
| `wrapper.java.jmx` | Register YAJSW's wrapper MBean with the application's MBean server | boolean | — | **unsupported** |  |
| `wrapper.registry.java_home` | Take the Java home of the application from the Windows registry | text | — | **unsupported** (P9) | *Verify:* meaning |
| `wrapper.app.pre.script` | Groovy script run before the application starts | text | — | **unsupported** |  |
| `wrapper.app.pre_main.script` | Groovy script run in the application JVM before its main method | text | — | **unsupported** |  |
| `wrapper.codebase` | Network launching: base URL the application's files are downloaded from | text | — | **unsupported** |  |
| `wrapper.cache` | Network launching: directory the downloaded files are cached in | path | — | **unsupported** |  |
| `wrapper.cache.local` | Network launching: copy local files into wrapper.cache too | boolean | — | **unsupported** |  |
| `wrapper.resource.<n>` | Network launching: file downloaded from the code base before the application starts | text | — | **unsupported** |  |
| `wrapper.wrapperJar` | Full path of the jar the wrapper runs from, when YAJSW is embedded in an application | path | — | **ignored** | nyajsw does not use YAJSW's jars |
| `wrapper.appJar` | Full path of the jar the application JVM uses, when YAJSW is embedded in an application | path | — | **ignored** | nyajsw does not use YAJSW's jars |
| `wrapper.groovy` | Groovy script run by the wrapper | text | — | **unsupported** |  |

## Lifecycle

| Key | Meaning | Type | Default | Status | Notes |
|---|---|---|---|---|---|
| `wrapper.control` | Coupling between the lifetimes of the wrapper and the application | one of `TIGHT`, `LOOSE`, `APPLICATION`, `WRAPPER` | `TIGHT` | **supported** | The exit action STOP keeps the wrapper running only under LOOSE and APPLICATION; a stop of the wrapper leaves the application running under LOOSE and WRAPPER (D20) |
| `wrapper.on_exit.default` | Action when the application exits with a code that has no wrapper.on_exit.&lt;code&gt; | one of `RESTART`, `SHUTDOWN`, `STOP` | `SHUTDOWN` | **supported** | STOP keeps the wrapper running only when wrapper.control is LOOSE or APPLICATION; otherwise it ends the wrapper like SHUTDOWN (D20) |
| `wrapper.on_exit.0` | Action when the application exits with code 0; when absent, wrapper.on_exit.default applies | one of `RESTART`, `SHUTDOWN`, `STOP` | — | **supported** | STOP keeps the wrapper running only when wrapper.control is LOOSE or APPLICATION; otherwise it ends the wrapper like SHUTDOWN (D20) |
| `wrapper.on_exit.<code>` | Action when the application exits with this exit code | one of `RESTART`, `SHUTDOWN`, `STOP` | — | **supported** | STOP keeps the wrapper running only when wrapper.control is LOOSE or APPLICATION; otherwise it ends the wrapper like SHUTDOWN (D20) |
| `wrapper.restart.delay` | Pause between the end of one invocation and the next start | seconds | `5` | **supported** |  |
| `wrapper.max_failed_invocations` | Failed invocations in a row after which the wrapper gives up | integer | `5` | **supported** | N failed invocations in a row end supervision, so at most N-1 restarts follow quick failures (D20). An invocation fails when it cannot be started, does not become ready within wrapper.startup.timeout, or ends within wrapper.successful_invocation_time; a launch error is retried whatever wrapper.on_exit says. Values below 1 count as 1 |
| `wrapper.successful_invocation_time` | Run time after which an invocation counts as successful | seconds | `60` | **supported** |  |
| `wrapper.startup.delay` | Pause before the application is started for the first time | seconds | `0` | **supported** |  |
| `wrapper.startup.timeout` | Time the application has to become ready | seconds | `30` | **supported** | 0 means no limit, as in the Tanuki format |
| `wrapper.shutdown.timeout` | Time the application has to stop gracefully before it is terminated | seconds | `30` | **supported** | YAJSW documents no default and bounds the stop by this and wrapper.jvm_exit.timeout together. In console mode that sum is the time the application has after Ctrl+C, which it receives too<br>*Verify:* default |
| `wrapper.jvm_exit.timeout` | Time added to wrapper.shutdown.timeout before the application is terminated | seconds | — | **supported** | When not set it adds nothing |
| `wrapper.pidfile` | File the wrapper writes its process id to | path | — | **supported** |  |
| `wrapper.lockfile` | File locked while the application runs, so that a second instance does not start | path | — | **unsupported** (P9) |  |
| `wrapper.java.pidfile` | File the wrapper writes the application's process id to | path | — | **supported** |  |
| `wrapper.ping.interval` | Heartbeat interval, mapped onto the agent liveness probe (D16) | seconds | `5` | **planned** (P7) |  |
| `wrapper.ping.timeout` | Heartbeat timeout, mapped onto the agent liveness probe (D16) | seconds | `30` | **planned** (P7) |  |
| `wrapper.restart.reload_configuration` | Reload the configuration file before each restart | boolean | — | **unsupported** (P9) | YAJSW default: false |
| `wrapper.restart.reload_cache` | Network launching: refresh the cached files when the configuration is reloaded | boolean | — | **unsupported** |  |
| `wrapper.restart.delay.script` | Groovy script that computes the restart delay | text | — | **unsupported** |  |
| `wrapper.restart.delay.script.args` | Arguments of wrapper.restart.delay.script | text | — | **unsupported** |  |
| `wrapper.stop.conf` | Configuration of a separate program that stops the application | path | — | **unsupported** (P9) |  |
| `wrapper.stopper` | Marks a configuration as the stop program named by wrapper.stop.conf | boolean | — | **unsupported** (P9) |  |
| `wrapper.exit_on_main_terminate` | In-JVM behaviour when the application's main method returns | text | — | **unsupported** | YAJSW default: -1, the application keeps running while it has non-daemon threads |
| `wrapper.exit_on_main_exception` | In-JVM behaviour when the application's main method throws | text | — | **unsupported** | YAJSW default: 999, the exit code used |
| `wrapper.exit_code.stop` | Exit code reported when the application stopped as asked | integer | — | **unsupported** (P9) | YAJSW default: 0 |
| `wrapper.exit_code.kill` | Exit code reported when the application had to be terminated | integer | — | **unsupported** (P9) | YAJSW default: 999 |
| `wrapper.exit_code.fatal` | Exit code reported when the application cannot start or its main method throws | integer | — | **unsupported** (P9) | YAJSW default: 999 |
| `wrapper.script.<state>` | Groovy script run when the application changes state, with its .args and .timeout | text | — | **unsupported** |  |
| `wrapper.script.reload` | Reload changed Groovy scripts before running them | boolean | — | **unsupported** |  |
| `wrapper.script.encoding` | Character encoding of the Groovy scripts | text | — | **unsupported** |  |
| `wrapper.condition.script` | Groovy script that decides when the application is started or stopped | text | — | **unsupported, fatal when present** | Fatal because ignoring it would keep the application running whatever the script decides |
| `wrapper.condition.script.args` | Arguments of wrapper.condition.script | text | — | **unsupported** |  |
| `wrapper.condition.cycle` | Interval at which wrapper.condition.script runs | seconds | — | **unsupported** |  |
| `wrapper.timer.<setting>` | Cron or interval timers that start, stop or restart the application | text | — | **unsupported** (P9) |  |
| `wrapper.monitor.<setting>` | Monitors that compare application metrics with a threshold and act on them | text | — | **unsupported** |  |
| `wrapper.filter.trigger.<n>` | Text in the application output that triggers an action | text | — | **unsupported** (P9) |  |
| `wrapper.filter.trigger-regex.<n>` | Regular expression in the application output that triggers an action | text | — | **unsupported** (P9) |  |
| `wrapper.filter.script.<n>` | Groovy script run when wrapper.filter.trigger.&lt;n&gt; matches | text | — | **unsupported** |  |
| `wrapper.filter.script.<n>.args` | Arguments of wrapper.filter.script.&lt;n&gt; | text | — | **unsupported** |  |
| `wrapper.filter.script.<n>.timeout` | Time one run of wrapper.filter.script.&lt;n&gt; may take | seconds | — | **unsupported** |  |
| `wrapper.filter.script.<n>.maxConcInvoc` | Runs of wrapper.filter.script.&lt;n&gt; allowed at the same time | integer | — | **unsupported** |  |
| `wrapper.filter.action.<n>` | Action when wrapper.filter.trigger.&lt;n&gt; matches | one of `RESTART`, `SHUTDOWN`, `NONE` | `RESTART` | **planned** (P9) |  |
| `wrapper.filter.missing.<setting>` | Filters that act when expected output stops appearing | text | — | **unsupported** (P9) |  |
| `wrapper.filter.debug.default` | Log the matching of output filters | boolean | — | **unsupported** | *Verify:* meaning |
| `wrapper.stop.strategy` | Ordered stop strategies tried before the application is terminated | list | agent,terminate when wrapper.agent.enabled is true (from P7), otherwise terminate | **supported**<br>nyajsw extension | Names ignore case; unknown ones are skipped with a warning and terminate is always the last step. The strategies before it share wrapper.shutdown.timeout plus wrapper.jvm_exit.timeout; when both are 0 they are skipped. Strategies: implementation plan, section 4.6 |
| `wrapper.stop.command` | Command run by the command stop strategy | text | — | **planned** (P7)<br>nyajsw extension |  |

## Logging

| Key | Meaning | Type | Default | Status | Notes |
|---|---|---|---|---|---|
| `wrapper.logfile` | Wrapper log file; may contain the ROLLNUM and YYYYMMDD tokens | path | — | **planned** (P4) |  |
| `wrapper.logfile.format` | Format tokens of log file lines | text | `LPTM` | **planned** (P4) | Tokens: L level, P prefix, D thread, T time, Z milliseconds, M message |
| `wrapper.logfile.loglevel` | Lowest level written to the log file | one of `NONE`, `FATAL`, `ERROR`, `STATUS`, `INFO`, `DEBUG` | `INFO` | **planned** (P4) |  |
| `wrapper.logfile.maxsize` | Size at which the log file rolls; 0 turns rolling by size off | size | `0` | **planned** (P4) |  |
| `wrapper.logfile.maxfiles` | Number of rolled log files kept, per day under the DATE roll mode | integer | — | **planned** (P4) |  |
| `wrapper.logfile.maxdays` | Days of log files kept under the DATE roll mode; -1 keeps all | integer | `-1` | **planned** (P4) |  |
| `wrapper.logfile.rollmode` | When the log file rolls, besides rolling by size | one of `JVM`, `WRAPPER`, `DATE` | — | **planned** (P4) | *Verify:* values |
| `wrapper.logfile.desc` | Roll by renaming only the newest file, which then gets the highest number | boolean | — | **unsupported** (P9) |  |
| `wrapper.log.encoding` | Character encoding of the wrapper log file | text | — | **planned** (P4) | YAJSW defaults to the JVM's default charset |
| `wrapper.console.format` | Format tokens of console lines | text | `PM` | **planned** (P4) | Same tokens as wrapper.logfile.format |
| `wrapper.console.loglevel` | Lowest level written to the console | one of `NONE`, `FATAL`, `ERROR`, `STATUS`, `INFO`, `DEBUG` | `INFO` | **supported** |  |
| `wrapper.console.visible` | Show a console window for the service | boolean | — | **unsupported** (P9) |  |
| `wrapper.console.title` | Title of the console window | text | — | **unsupported** (P9) |  |
| `wrapper.console.minimized` | Start the visible console window minimized | boolean | — | **unsupported** (P9) |  |
| `wrapper.console.pipestreams` | Pipe the application's standard streams to the wrapper instead of copying them | boolean | — | **ignored** | nyajsw always pipes them, as if true |
| `wrapper.console.use_interpolated` | Whether Java options of the wrapper JVM are passed on to the application | boolean | — | **unsupported** |  |
| `wrapper.app.status.log.lines` | Application output lines logged at the STATUS level | integer | — | **unsupported** |  |
| `wrapper.debug` | Raise the wrapper log level to DEBUG | boolean | `false` | **supported** | Raises wrapper.console.loglevel; wrapper.debug.level chooses which debug messages are written |
| `wrapper.debug.level` | Detail of the wrapper.debug output, from 0 (least) to 3 (all) | integer | `3` | **supported** | Read only when wrapper.debug is true. 0: no debug messages; 1: starting and stopping the application; 2: also the steps of supervision (stop steps, readiness checks, pid files); 3: everything. Another value is an error while wrapper.debug is true, a warning otherwise |
| `wrapper.syslog.<setting>` | Logging to syslog (facility, ident, loglevel) | text | — | **unsupported** |  |

## Windows service

| Key | Meaning | Type | Default | Status | Notes |
|---|---|---|---|---|---|
| `wrapper.ntservice.name` | Service name | text | the simple name of the application main class | **planned** (P5) | *Verify:* default |
| `wrapper.ntservice.displayname` | Display name of the service | text | — | **planned** (P5) |  |
| `wrapper.ntservice.description` | Description of the service | text | — | **planned** (P5) |  |
| `wrapper.ntservice.starttype` | Start type of the service | one of `AUTO_START`, `DEMAND_START`, `DELAYED_AUTO_START`, `DISABLED`; alias `DELAY_START` = `DELAYED_AUTO_START` | `AUTO_START` | **planned** (P5) |  |
| `wrapper.ntservice.dependency.<n>` | Service that must run before this one | text | — | **planned** (P5) |  |
| `wrapper.ntservice.account` | Account the service runs as; granted the right to log on as a service at install | text | — | **planned** (P5) | When absent the service runs as LocalSystem |
| `wrapper.ntservice.password` | Password of wrapper.ntservice.account | text | — | **planned** (P5) | Secret: never logged or shown. |
| `wrapper.ntservice.interactive` | Let the service interact with the desktop | boolean | — | **ignored** | Obsolete since Windows Vista isolated services in session 0 |
| `wrapper.ntservice.failure_actions.<setting>` | SCM recovery actions (reset_period, reboot_msg, command, actions, actions_delay) | text | — | **unsupported** (P9) |  |
| `wrapper.ntservice.additional.<n>` | JVM option for the wrapper JVM, written into the service binary path (D12) | text | — | **planned** (P5) |  |
| `wrapper.ntservice.java.command` | Java executable of the wrapper JVM that runs the service | path | javaw from the java.home of the JVM that installs the service (D12) | **planned** (P5) | YAJSW defaults to the application's Java command; nyajsw needs Java 21 here |
| `wrapper.ntservice.java.customProcName` | Run the service from a renamed copy of the Java executable | text | — | **unsupported** (P9) |  |
| `wrapper.ntservice.autoreport.startup` | Report the service as running as soon as the application starts | boolean | — | **unsupported** | Readiness probes decide when nyajsw reports RUNNING (wrapper.readiness.type); with false, YAJSW waits for the application to report through YAJSW's API |
| `wrapper.ntservice.autoreport.waitready` | Show the service as waiting in the tray icon until the application reports | boolean | — | **unsupported** |  |
| `wrapper.ntservice.reduce_signals` | Start the application JVM with -Xrs in service mode, so a user logging off does not stop it | boolean | `true` | **planned** (P6) |  |
| `wrapper.ntservice.logon_active_session` | Start the application in the session of the logged-on user | boolean | — | **unsupported** | *Verify:* meaning |
| `wrapper.ntservice.desktop` | Desktop the application is started on | text | — | **unsupported** | *Verify:* meaning |
| `wrapper.save_interpolated` | Store the resolved ${...} values with the installed service | boolean | — | **ignored** | nyajsw resolves them again at every start, as if false (plan section 3.4) |
| `wrapper.windows.cluster.<setting>` | Groovy script run when a Windows failover cluster group moves, to start the application only on the active node | text | — | **unsupported, fatal when present** (P9) | Fatal because ignoring it would start the application on every node |

## Readiness, liveness and agent

| Key | Meaning | Type | Default | Status | Notes |
|---|---|---|---|---|---|
| `wrapper.readiness.type` | Readiness probes that must all pass before the service reports RUNNING | list | `process` | **planned** (P7)<br>nyajsw extension | Probe types and their settings: implementation plan, section 4.7 |
| `wrapper.readiness.interval` | Pause between readiness checks | seconds | `1` | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.readiness.tcp.host` | Host the tcp readiness probe connects to | text | `127.0.0.1` | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.readiness.tcp.port` | Port the tcp readiness probe connects to | integer | — | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.readiness.http.url` | URL the http readiness probe requests | text | — | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.readiness.http.status` | HTTP status codes the http readiness probe accepts | text | `200-399` | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.readiness.http.timeout` | Time one http readiness request may take | seconds | — | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.readiness.log.pattern` | Regular expression the log readiness probe waits for | text | — | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.readiness.log.file` | File the log readiness probe reads | path | — | **planned** (P7)<br>nyajsw extension | When absent the probe reads the captured application output |
| `wrapper.readiness.file.path` | File whose existence the file readiness probe waits for | path | — | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.readiness.command` | Command whose exit code 0 the command readiness probe waits for | text | — | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.readiness.command.timeout` | Time one run of wrapper.readiness.command may take | seconds | — | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.readiness.jmx.url` | JMX service URL the jmx readiness probe connects to | text | — | **unsupported** (P9)<br>nyajsw extension |  |
| `wrapper.readiness.jmx.attribute` | MBean attribute the jmx readiness probe reads | text | — | **unsupported** (P9)<br>nyajsw extension |  |
| `wrapper.readiness.jmx.value` | Attribute value the jmx readiness probe waits for | text | — | **unsupported** (P9)<br>nyajsw extension |  |
| `wrapper.liveness.type` | Liveness probes checked while the application runs | list | agent when wrapper.agent.enabled is true (from P7), otherwise none (D16) | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.liveness.interval` | Pause between liveness checks | seconds | `10` | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.liveness.failures` | Consecutive liveness failures before wrapper.liveness.action is taken | integer | `3` | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.liveness.action` | Action when the liveness probes keep failing | one of `RESTART`, `SHUTDOWN` | `RESTART` | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.agent.enabled` | Load the nyajsw agent into the application JVM | boolean | `true` | **planned** (P7)<br>nyajsw extension |  |
| `wrapper.port` | Port of the channel between the wrapper and the application | integer | — | **ignored** | The nyajsw agent listens on a loopback port chosen at launch |
| `wrapper.port.min` | Lowest port tried for the channel between the wrapper and the application | integer | — | **ignored** | The nyajsw agent listens on a loopback port chosen at launch |
| `wrapper.port.max` | Highest port tried for the channel between the wrapper and the application | integer | — | **ignored** | The nyajsw agent listens on a loopback port chosen at launch |

## Out of scope

| Key | Meaning | Type | Default | Status | Notes |
|---|---|---|---|---|---|
| `wrapper.daemon.dir` | POSIX daemon script directory | path | — | **unsupported** (P9) |  |
| `wrapper.daemon.pid.dir` | POSIX daemon pid file directory | path | — | **unsupported** (P9) |  |
| `wrapper.daemon.run_level_dir` | POSIX run-level directory | path | — | **unsupported** (P9) |  |
| `wrapper.daemon.update_rc` | POSIX update-rc.d invocation | text | — | **unsupported** (P9) |  |
| `wrapper.daemon.template` | Template of the POSIX daemon script | path | — | **unsupported** (P9) |  |
| `wrapper.launchd.dir` | macOS launchd agent directory | path | — | **unsupported** |  |
| `wrapper.plist.template` | Template of the macOS launchd property list | path | — | **unsupported** |  |
| `wrapper.fork_hack` | POSIX workaround for starting the application process | boolean | — | **unsupported** |  |
| `wrapper.posix_spawn` | Start the application with posix_spawn | boolean | — | **unsupported** |  |
| `wrapper.posix_vfork` | Start the application with vfork | boolean | — | **unsupported** |  |
| `wrapper.tray` | System tray icon | boolean | — | **unsupported** |  |
| `wrapper.tray.port` | Port of the system tray icon | integer | — | **unsupported** | *Verify:* name |
| `wrapper.tray.icon` | Image of the system tray icon | path | — | **unsupported** |  |
| `wrapper.tray.<setting>` | Other settings of the system tray icon: menu, dialogs, texts, look and feel | text | — | **unsupported** |  |
| `wrapper.jmx` | JMX remote console | boolean | — | **unsupported** |  |
| `wrapper.jmx.rmi.port` | RMI port of the JMX remote console | integer | — | **unsupported** |  |
| `wrapper.jmx.rmi.user` | User of the JMX remote console | text | — | **unsupported** |  |
| `wrapper.jmx.rmi.password` | Password of the JMX remote console | text | — | **unsupported** | Secret: never logged or shown. |

## nyajsw extensions

Keys YAJSW does not know; they live in the same `wrapper.*` namespace:

- `wrapper.stop.strategy`
- `wrapper.stop.command`
- `wrapper.readiness.type`
- `wrapper.readiness.interval`
- `wrapper.readiness.tcp.host`
- `wrapper.readiness.tcp.port`
- `wrapper.readiness.http.url`
- `wrapper.readiness.http.status`
- `wrapper.readiness.http.timeout`
- `wrapper.readiness.log.pattern`
- `wrapper.readiness.log.file`
- `wrapper.readiness.file.path`
- `wrapper.readiness.command`
- `wrapper.readiness.command.timeout`
- `wrapper.readiness.jmx.url`
- `wrapper.readiness.jmx.attribute`
- `wrapper.readiness.jmx.value`
- `wrapper.liveness.type`
- `wrapper.liveness.interval`
- `wrapper.liveness.failures`
- `wrapper.liveness.action`
- `wrapper.agent.enabled`
