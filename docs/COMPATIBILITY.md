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
| supported | 1 |
| planned | 65 |
| ignored | 2 |
| unsupported | 39 |
| unsupported, fatal when present | 2 |
| *of which nyajsw extensions* | 24 |
| **total** | 109 |

## Configuration file

| Key | Meaning | Type | Default | Status | Notes |
|---|---|---|---|---|---|
| `wrapper.conf.encoding` | Character encoding of the configuration file and its includes | text | UTF-8, with a windows-1252 fallback and a warning for files that are not valid UTF-8 (D7) | **supported** | A Java charset name, without variables. Set by a system property (required for UTF-16) or in the file itself. Decoding is strict: a byte that does not fit is an error at its line. YAJSW defaults to the platform charset instead |

## Java process

| Key | Meaning | Type | Default | Status | Notes |
|---|---|---|---|---|---|
| `wrapper.java.command` | Java executable that runs the application | path | java from the wrapper JVM's java.home, or javaw when wrapper.java.command.javaw is true | **planned** (P2) |  |
| `wrapper.java.command.javaw` | Use javaw instead of java for the default Java executable | boolean | `false` | **planned** (P2) | *Verify:* default |
| `wrapper.java.command.minVersion` | Lowest Java version accepted when searching for a JVM | text | — | **unsupported** (P9) |  |
| `wrapper.java.command.maxVersion` | Highest Java version accepted when searching for a JVM | text | — | **unsupported** (P9) |  |
| `wrapper.java.command.jreOnly` | Accept only a JRE when searching for a JVM | boolean | — | **unsupported** (P9) |  |
| `wrapper.java.command.jdkOnly` | Accept only a JDK when searching for a JVM | boolean | — | **unsupported** (P9) |  |
| `wrapper.java.command.preferJre` | Prefer a JRE when searching for a JVM | boolean | — | **unsupported** (P9) |  |
| `wrapper.java.command.preferJdk` | Prefer a JDK when searching for a JVM | boolean | — | **unsupported** (P9) |  |
| `wrapper.java.customProcName` | Run the application from a renamed copy of the Java executable | text | — | **unsupported** (P9) |  |
| `wrapper.java.app.mainclass` | Main class of the application | text | — | **planned** (P2) |  |
| `wrapper.java.app.jar` | Executable jar of the application, instead of a main class | path | — | **planned** (P2) |  |
| `wrapper.java.mainclass` | YAJSW's in-JVM bootstrap class | text | — | **ignored** | nyajsw starts the application main class directly; the optional agent replaces the bootstrap |
| `wrapper.app.parameter.<n>` | Program argument passed to the application | text | — | **planned** (P2) |  |
| `wrapper.java.classpath.<n>` | Classpath entry of the application; * and ** wildcards allowed | path | — | **planned** (P2) |  |
| `wrapper.java.library.path.<n>` | Entry of the application's java.library.path | path | — | **planned** (P2) |  |
| `wrapper.java.additional.<n>` | JVM option or -D system property of the application | text | — | **planned** (P2) |  |
| `wrapper.java.initmemory` | Initial heap of the application in MB (-Xms) | integer | — | **planned** (P2) |  |
| `wrapper.java.maxmemory` | Maximum heap of the application in MB (-Xmx) | integer | — | **planned** (P2) |  |
| `wrapper.java.initmemory.relative` | Initial heap as a percentage of physical memory | integer | — | **unsupported** (P9) |  |
| `wrapper.java.maxmemory.relative` | Maximum heap as a percentage of physical memory | integer | — | **unsupported** (P9) |  |
| `wrapper.app.env.<variable>` | Environment variable set for the application | text | — | **planned** (P2) |  |
| `wrapper.working.dir` | Working directory of the application | path | the directory of the configuration file (D13) | **planned** (P2) | *Verify:* default |
| `wrapper.tmp.path` | Temporary directory, also used as jna.tmpdir | path | — | **planned** (P2) |  |
| `wrapper.image` | Non-Java executable to run instead of a JVM | path | — | **planned** (P2) | Optional plan item P2.6 |
| `wrapper.priority` | Process priority of the application | text | — | **unsupported** (P9) |  |
| `wrapper.affinity` | CPU affinity of the application | text | — | **unsupported** (P9) |  |
| `wrapper.app.account` | Run the application as another user | text | — | **unsupported, fatal when present** (P9) | Fatal because ignoring it would run the application under the wrong account |
| `wrapper.app.password` | Password of wrapper.app.account | text | — | **unsupported, fatal when present** (P9) | Secret: never logged or shown. |
| `wrapper.java.monitor.<setting>` | Deadlock, heap and garbage-collection monitoring | text | — | **unsupported** (P9) | *Verify:* name |
| `wrapper.groovy` | Groovy script run by the wrapper | text | — | **unsupported** |  |

## Lifecycle

| Key | Meaning | Type | Default | Status | Notes |
|---|---|---|---|---|---|
| `wrapper.control` | Coupling between the lifetimes of the wrapper and the application | one of `TIGHT`, `LOOSE`, `APPLICATION`, `WRAPPER` | `TIGHT` | **planned** (P3) | *Verify:* default |
| `wrapper.on_exit.default` | Action when the application exits with a code that has no wrapper.on_exit.&lt;code&gt; | one of `RESTART`, `SHUTDOWN`, `STOP` | `RESTART` | **planned** (P3) | *Verify:* default |
| `wrapper.on_exit.0` | Action when the application exits with code 0 | one of `RESTART`, `SHUTDOWN`, `STOP` | `SHUTDOWN` | **planned** (P3) | *Verify:* default |
| `wrapper.on_exit.<code>` | Action when the application exits with this exit code | one of `RESTART`, `SHUTDOWN`, `STOP` | — | **planned** (P3) |  |
| `wrapper.restart.delay` | Pause between the end of one invocation and the next start | seconds | — | **planned** (P3) | *Verify:* default |
| `wrapper.max_failed_invocations` | Failed invocations in a row after which the wrapper gives up | integer | `5` | **planned** (P3) |  |
| `wrapper.successful_invocation_time` | Run time after which an invocation counts as successful | seconds | `300` | **planned** (P3) |  |
| `wrapper.startup.timeout` | Time the application has to become ready | seconds | `30` | **planned** (P3) |  |
| `wrapper.shutdown.timeout` | Time the application has to stop gracefully before it is terminated | seconds | `30` | **planned** (P3) |  |
| `wrapper.pidfile` | File the wrapper writes its process id to | path | — | **planned** (P3) |  |
| `wrapper.java.pidfile` | File the wrapper writes the application's process id to | path | — | **planned** (P3) |  |
| `wrapper.ping.interval` | Heartbeat interval, mapped onto the agent liveness probe (D16) | seconds | — | **planned** (P7) | *Verify:* name |
| `wrapper.ping.timeout` | Heartbeat timeout, mapped onto the agent liveness probe (D16) | seconds | — | **planned** (P7) |  |
| `wrapper.restart.reload_configuration` | Reload the configuration file before each restart | boolean | — | **unsupported** (P9) | *Verify:* name |
| `wrapper.stop.conf` | Configuration of a separate program that stops the application | path | — | **unsupported** (P9) |  |
| `wrapper.exit_on_main_terminate` | In-JVM behaviour when the application's main method returns | text | — | **unsupported** | *Verify:* name |
| `wrapper.exit_on_main_exception` | In-JVM behaviour when the application's main method throws | text | — | **unsupported** | *Verify:* name |
| `wrapper.filter.trigger.<n>` | Text in the application output that triggers an action | text | — | **unsupported** (P9) |  |
| `wrapper.filter.script.<n>` | Groovy script run when wrapper.filter.trigger.&lt;n&gt; matches | text | — | **unsupported** |  |
| `wrapper.filter.script.<n>.args` | Arguments of wrapper.filter.script.&lt;n&gt; | text | — | **unsupported** |  |
| `wrapper.filter.action.<n>` | Action when wrapper.filter.trigger.&lt;n&gt; matches | one of `RESTART`, `SHUTDOWN` | — | **planned** (P9)<br>nyajsw extension |  |
| `wrapper.stop.strategy` | Ordered stop strategies tried before the application is terminated | list | agent,terminate when wrapper.agent.enabled is true (from P7), otherwise terminate | **planned** (P3)<br>nyajsw extension | Strategies and their order: implementation plan, section 4.6 |
| `wrapper.stop.command` | Command run by the command stop strategy | text | — | **planned** (P7)<br>nyajsw extension |  |

## Logging

| Key | Meaning | Type | Default | Status | Notes |
|---|---|---|---|---|---|
| `wrapper.logfile` | Wrapper log file; may contain the ROLLNUM and YYYYMMDD tokens | path | — | **planned** (P4) |  |
| `wrapper.logfile.format` | Format tokens of log file lines | text | — | **planned** (P4) |  |
| `wrapper.logfile.loglevel` | Lowest level written to the log file | one of `NONE`, `FATAL`, `ERROR`, `STATUS`, `INFO`, `DEBUG` | — | **planned** (P4) | *Verify:* values |
| `wrapper.logfile.maxsize` | Size at which the log file rolls | size | — | **planned** (P4) |  |
| `wrapper.logfile.maxfiles` | Number of rolled log files kept | integer | — | **planned** (P4) |  |
| `wrapper.logfile.rollmode` | When the log file rolls | one of `NONE`, `SIZE`, `DATE`, `SIZE_OR_DATE` | — | **planned** (P4) | *Verify:* values |
| `wrapper.console.format` | Format tokens of console lines | text | — | **planned** (P4) |  |
| `wrapper.console.loglevel` | Lowest level written to the console | one of `NONE`, `FATAL`, `ERROR`, `STATUS`, `INFO`, `DEBUG` | — | **planned** (P4) | *Verify:* values |
| `wrapper.console.visible` | Show a console window for the service | boolean | — | **unsupported** (P9) |  |
| `wrapper.console.title` | Title of the console window | text | — | **unsupported** (P9) |  |
| `wrapper.console.pipestreams` | How the application's output streams are piped | boolean | — | **unsupported** | *Verify:* name |
| `wrapper.debug` | Raise the wrapper log level to DEBUG | boolean | `false` | **planned** (P4) |  |
| `wrapper.java.debug` | Verbose logging about the application JVM | boolean | `false` | **planned** (P4) | *Verify:* meaning |
| `wrapper.syslog.<setting>` | Logging to syslog | text | — | **unsupported** | *Verify:* name |

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
| `wrapper.ntservice.failure_actions.<setting>` | SCM recovery actions | text | — | **unsupported** (P9) | *Verify:* name |
| `wrapper.ntservice.java.additional.<n>` | JVM option for the wrapper JVM, written into the service binary path (D12) | text | — | **planned** (P5)<br>nyajsw extension |  |

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

## Out of scope

| Key | Meaning | Type | Default | Status | Notes |
|---|---|---|---|---|---|
| `wrapper.daemon.dir` | POSIX daemon script directory | path | — | **unsupported** (P9) |  |
| `wrapper.daemon.pid.dir` | POSIX daemon pid file directory | path | — | **unsupported** (P9) |  |
| `wrapper.daemon.run_level_dir` | POSIX run-level directory | path | — | **unsupported** (P9) |  |
| `wrapper.update_rc` | POSIX update-rc.d invocation | text | — | **unsupported** (P9) |  |
| `wrapper.tray` | System tray icon | boolean | — | **unsupported** |  |
| `wrapper.tray.port` | Port of the system tray icon | integer | — | **unsupported** |  |
| `wrapper.tray.icon` | Image of the system tray icon | path | — | **unsupported** |  |
| `wrapper.jmx` | JMX remote console | boolean | — | **unsupported** |  |
| `wrapper.jmx.rmi.port` | RMI port of the JMX remote console | integer | — | **unsupported** |  |
| `wrapper.jmx.rmi.user` | User of the JMX remote console | text | — | **unsupported** |  |
| `wrapper.jmx.rmi.password` | Password of the JMX remote console | text | — | **unsupported** | Secret: never logged or shown. |

## nyajsw extensions

Keys YAJSW does not know; they live in the same `wrapper.*` namespace:

- `wrapper.filter.action.<n>`
- `wrapper.stop.strategy`
- `wrapper.stop.command`
- `wrapper.ntservice.java.additional.<n>`
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
