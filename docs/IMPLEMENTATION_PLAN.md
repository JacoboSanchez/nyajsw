# nyajsw — Implementation Plan

Status legend: `[ ]` not started · `[~]` in progress · `[x]` done
Last updated: 2026-11-08

How to use this document: pick the next unchecked item in §5, implement it following
[AGENTS.md](../AGENTS.md), and update the checkbox, the decisions log (§9) and the compatibility
matrix (the key registry, regenerating `docs/COMPATIBILITY.md`, §7) in the same commit.

---

## 1. Summary

nyajsw is a clean-room replacement for YAJSW (Yet Another Java Service Wrapper). YAJSW still
ships releases and runs production services through a `wrapper.conf` file that many deployments
depend on, but its code base predates modern Java and the owner wants to move away from it
(§10 Q9). nyajsw keeps that configuration surface (keys, semantics, command-line switches and
helper scripts) and reimplements the behaviour on Java 21, using JNA to call the generic Windows
Service Control Manager (SCM) API, with a cleaner internal design and a test suite that runs on
every platform.

The two things a user of YAJSW must be able to do on day one:

1. Point nyajsw at an existing `wrapper.conf` and get the same Windows service, with warnings for
   anything nyajsw does not support.
2. Keep tuning the Java process from the configuration file: JVM executable, system properties
   (`-D`), JVM options, memory, classpath, main class or jar, program arguments, working directory
   and environment variables.

Beyond parity, nyajsw adds **pluggable readiness and liveness probes** so "the service is started"
can mean "the application actually answers", not just "a process exists".

## 2. Goals and non-goals

### Goals

* **G1 Configuration compatibility.** Existing `wrapper.conf` files load unchanged. Unsupported keys
  produce clear warnings, never silent behaviour changes.
* **G2 Java process control from configuration.** Full control of the application JVM command line,
  including a different JVM than the one running the wrapper.
* **G3 Windows service integration through generic Win32 APIs** (advapi32 SCM functions) via JNA:
  install, remove, start, stop, query, and run *as* the service (control dispatcher, status
  reporting).
* **G4 Supervision.** Restart policy by exit code, startup/shutdown timeouts, failed-invocation
  limits, graceful stop before termination.
* **G5 Alternative start validation.** Readiness probes (process, TCP, HTTP, log pattern, file,
  command, in-JVM agent) decide when to report `RUNNING` to the SCM; liveness probes decide when to
  restart.
* **G6 Operability.** Wrapper log with rotation compatible with `wrapper.logfile.*`, captured
  application output, understandable errors (Win32 messages, elevation hints).
* **G7 Maintainability.** Small, cohesive classes; extension through registries; no duplicated
  logic; unit tests on all OSes; Windows integration tests isolated in their own TestNG group.

### Non-goals (for now)

* POSIX daemons (`wrapper.daemon.*`, `wrapper.launchd.dir`). The platform abstraction leaves the door
  open; nothing else is designed for it yet.
* System tray, Groovy scripting/filters, JMX remote console, web console, running the application
  under a different user than the service (`wrapper.app.account`).
* Bit-for-bit log format parity with YAJSW. Format *tokens* are honoured; incidental spacing is not.
* Reusing any YAJSW/Tanuki source. nyajsw is Apache-2.0 and written from the public
  configuration documentation only (see AGENTS.md §1).

## 3. Compatibility strategy

### 3.1 Configuration

* Same file format: `key=value` lines, `#`/`!` comments, `.N` indexed keys, `${name}` substitution
  (plus the Commons Configuration prefixes YAJSW accepts, `${sys:name}` and `${env:NAME}`), and
  include directives in both spellings seen in YAJSW files, `#include <file>` and
  `include = <file>`. Details in §4.5.
* Every key is classified in the **key registry** (§4.5) with one status: `SUPPORTED`, `PLANNED`,
  `IGNORED` (accepted, warned, no effect), `UNSUPPORTED` (warned) or
  `UNSUPPORTED_FATAL` (validation fails when the key is present, because ignoring it would change
  behaviour dangerously — e.g. `wrapper.app.account`, which would otherwise run the application
  under the wrong account). Partial support is expressed in the key's notes. Whether a key is a nyajsw
  extension is a separate attribute, its origin (`YAJSW`/`NYAJSW`), so an extension can be
  `PLANNED` or `SUPPORTED` like any other key. The registry generates
  `docs/COMPATIBILITY.md` (§7).
* Enumerated values accept YAJSW spelling and well-known aliases
  (`DELAYED_AUTO_START`/`DELAY_START`, log levels `NONE FATAL ERROR STATUS INFO DEBUG`).
* Groovy expressions inside `${...}` are detected and rejected with an explanatory error.

### 3.2 Command line and scripts

YAJSW switches are kept so existing operational runbooks keep working:

| Switch | Long form | Action |
|---|---|---|
| `-c <conf>` | `--console <conf>` | Run the application in the foreground under the supervisor |
| `-i <conf>` | `--install <conf>` | Install the Windows service |
| `-r <conf>` | `--remove <conf>` | Remove (uninstall) the service |
| `-t <conf>` | `--start <conf>` | Start the installed service |
| `-p <conf>` | `--stop <conf>` | Stop the installed service |
| `-q <conf>` | `--query <conf>` | Print service status |
| — | `--service <conf>` | Internal: entry point used by the SCM (the service binary path) |
| — | `--help`, `--version` | |

Scripts shipped in `bin/` mirror YAJSW names: `runConsole.bat`, `installService.bat`,
`uninstallService.bat`, `startService.bat`, `stopService.bat`, `queryService.bat`, plus a generic
`nyajsw.bat`. They default to `..\conf\wrapper.conf` like YAJSW's.

Exit codes: `0` success · `1` unexpected error · `2` configuration error · `3` service operation
failed · `4` unsupported platform. Console mode returns the application's own exit code, which may
coincide with the values above; the wrapper log states which one it was.

### 3.3 Behaviour

Where YAJSW semantics are documented, nyajsw follows them: `wrapper.on_exit.*` actions, restart
delay, `wrapper.max_failed_invocations` / `wrapper.successful_invocation_time`, startup and
shutdown timeouts, heartbeat (`wrapper.ping.*`) when the agent is enabled, `wrapper.control`
coupling, log rotation by size/date. Defaults match YAJSW where known; each default is recorded in
the key registry so divergences are visible.

### 3.4 Deliberate differences

* No in-JVM bootstrap class (`wrapper.java.mainclass=…WrapperJVMMain`). nyajsw uses a tiny
  `-javaagent` instead (§4.7), which can be disabled; the application `main` is never wrapped.
* Readiness is probe-based; `RUNNING` is reported to the SCM only when the configured probes pass.
* The service JVM defaults to the installing JVM's `javaw.exe` (D12), not to the application's Java
  command as YAJSW's `wrapper.ntservice.java.command` does, because the wrapper needs Java 21.
* `${...}` is resolved again at every service start; YAJSW stores the values resolved at install
  time (`wrapper.save_interpolated`, default `true`), so a variable that changed since then now
  takes effect.
* `wrapper.ntservice.autoreport.startup=false` (wait for the application to report through
  YAJSW's API) is not honoured; readiness probes take that role.
* Without `wrapper.logfile` the log is `wrapper.log` beside the configuration file (D13), not beside
  the wrapper executable (YAJSW 11.01+: its `log` folder); a blank value turns the file off in both.
  The time fields of the log formats are nyajsw's (`yyyy/MM/dd HH:mm:ss`), since YAJSW documents
  none (P4.2). `wrapper.logfile.maxsize` also rolls files of the `JVM` roll mode, which YAJSW
  describes as without a size limit (P4.3b).
* A service registered by YAJSW does not run nyajsw by swapping jars: it is removed and installed
  again with `-i` on the same `wrapper.conf` (§10 Q8, D17).
* Extensions (probes, stop strategies) are new `wrapper.*`
  keys tagged as nyajsw extensions in the matrix.

## 4. Architecture

### 4.1 Process model

```
 Windows Service Control Manager (services.exe)
     │  StartService / ControlService(STOP) / QueryServiceStatusEx      (advapi32 via JNA)
     ▼
 ┌────────────────────────────────────────────────────────────────────────────┐
 │ nyajsw wrapper JVM (Java 21)                                               │
 │   cli.Main --service wrapper.conf                                          │
 │   platform.windows.WindowsServiceHost  StartServiceCtrlDispatcher,         │
 │                                        SetServiceStatus                    │
 │   lifecycle.Supervisor                 state machine, restart policy,      │
 │                                        stop strategies                     │
 │   probe.*                              readiness → RUNNING, liveness →     │
 │                                        restart                             │
 │   log.WrapperLog                       wrapper.log + captured app output   │
 └──────────────────────────────┬─────────────────────────────────────────────┘
                                │ ProcessBuilder: stdout/stderr piped, env, working dir
                                ▼
 ┌────────────────────────────────────────────────────────────────────────────┐
 │ application JVM (any Java ≥ 8, chosen by wrapper.java.command)             │
 │   user's main class / jar + wrapper.java.additional.N …                    │
 │   [optional] nyajsw-agent (-javaagent): heartbeat, graceful stop           │
 └────────────────────────────────────────────────────────────────────────────┘
```

Console mode (`-c`) is the same wrapper without the service host: the supervisor runs in the
foreground and Ctrl+C triggers the stop sequence (§4.6).

### 4.2 Maven modules

| Module | Purpose | Java |
|---|---|---|
| `nyajsw-agent` | `premain` agent loaded into the application JVM: connects back to the wrapper over loopback, answers pings, performs `System.exit` on `STOP` so shutdown hooks run. Zero dependencies. Also hosts `AgentProtocol`, the single definition of the wire protocol. | 8 bytecode (tests compiled for 21) |
| `nyajsw-wrapper` | Everything else: configuration, launcher, supervisor, probes, logging, platform (JNA), CLI. Depends on `nyajsw-agent` for `AgentProtocol` (allowed: Java 8 bytecode, no transitive deps). The agent never depends on the wrapper. | 21 |
| `nyajsw-dist` | Assembly: shaded `nyajsw.jar` (wrapper + JNA, `com.sun.jna` **not** relocated so native lookup keeps working), `nyajsw-agent.jar`, `bin/*.bat`, `conf/` templates, zip. | 21 |

The agent must be a separate artifact: `-javaagent` needs a standalone jar, and it must load on
older JVMs than the wrapper requires.

### 4.3 Packages and core abstractions (`io.github.jacobosanchez.nyajsw`)

| Package | Responsibility | Key types |
|---|---|---|
| *(root)* | Exception hierarchy shared by all packages. | `NyajswException` (root) |
| `cli` | Argument parsing, command dispatch, exit codes. The only place in the wrapper that calls `System.exit` (and `Runtime.halt`, in the console shutdown hook). | `Main`, `Command`, `ExitCode`, `ConsoleMode` (runs the supervisor since P3.6), `ServiceMode` and `ServiceStatusReporter` (P6.2), `Supervision` (the stack both modes run) |
| `config` | Parse, include, interpolate, layer and validate configuration; typed views; key registry. | `ConfigLoader` → `LoadedConfig` (with `ConfigLayer`), `ConfigSource` + `FileConfigSource` / `SystemPropertyConfigSource`, `ConfigEntry`, `ConfigLocation`, `SourceContent`, `Interpolator`, `ConfigKeys` (registry contents), `KeyRegistry` (lookup), `KeyCollector` (key families), `KeySpec`, `KeyPattern`, `KeyStatus`, `KeyOrigin`, `KeyType`, `KeyDefault`, `KeyCategory`, `Unverified`, `ConfigValidator`, `ValueParser`, `SecretMasker`, `ConfigDump`, `CompatibilityDocument`, `WrapperConfig` with `JavaConfig`, `ServiceConfig`, `LogConfig`, `LifecycleConfig`, `ProbeConfig` (records, built through `ConfigValues`), `Secret`, configuration enums `ExitAction`, `ControlMode`, `StartType`, `RollMode`, `LogLevel`, `FailureAction` (the registry declares their values from them), `ConfigException`, `ConfigWarning` |
| `launch` | Build the application command line and run it. | `JavaCommandBuilder` → `JavaCommand` (argument array, secret-masking loggable form), `ClasspathExpander`, `ApplicationProcess` (a `RunningApplication`, started per invocation through an `ApplicationLauncher`), `OutputPump`, `LaunchException` |
| `lifecycle` | Supervision state machine and policies. | `Supervisor`, `State`, `Scheduler` + `ExecutorScheduler`, `ExitActionResolver` (uses `config.ExitAction`), `RestartPolicy`, `StopStrategy` (SPI) + `StopStrategyRegistry`, `TerminateStopStrategy`, `CommandStopStrategy`, `AgentStopStrategy`, `PidFiles` |
| `probe` | Readiness/liveness checks. | `Probe` (SPI), `ProbeContext`, `ProbeResult`, `ProbeRegistry`, `ProbeRunner`, `ProcessProbe`, `TcpProbe`, `HttpProbe`, `LogPatternProbe`, `FileProbe`, `CommandProbe`, `AgentProbe` |
| `agentlink` | Wrapper side of the agent channel (listener, per-connection session, the agent's jar), used by `launch`, `probe`, `lifecycle` and `cli`. | `AgentListener`, `AgentChannel`, `AgentJar` |
| `log` | Wrapper logging facade and rolling file output honouring `wrapper.logfile.*`. | `WrapperLog` (levels: `config.LogLevel`, as `java.util.logging` levels in `WrapperLevel`), `ConsoleSink`, `LineFormatter` (renders a `config.LineFormat`, P4.2), `RollingFileSink`, `LogFileNames` |
| `platform` | OS boundary. Everything the core needs from the OS, as interfaces. | `Platform` (chosen by `Platforms`, P5.1), `ServiceController` (install/remove/start/stop/status), `ServiceHost` (run as service, report status) with `ServiceBody`, `ServiceContext`, `ServiceControl` and `ServiceExit`, `ServiceStatus` + `ServiceState`, `ProcessTools` and `ProcessJob` (P6.4), `ServiceException` (SCM operations), `PlatformException` (OS-level) and `UnsupportedPlatformException` (exit code 4), `HostOs` |
| `platform.windows` | Windows implementations. | `ScmApi`, `ServiceHostApi` and `ProcessApi` (thin facades, one method per Win32 call we use), `JnaScmApi`, `JnaServiceHostApi`, `JnaProcessApi`, `WindowsServiceController`, `WindowsServiceHost`, `ServiceCommandLine`, `WindowsProcessTools`, `KernelHandle`, `Win32Errors`, `Win32Failures` |
| `platform.windows.jna` | Hand-written JNA bindings for what `jna-platform` lacks (§4.4). | `Advapi32Lsa`, `Kernel32Jobs`, structures |
| `platform.unsupported` | Console-only fallback for non-Windows hosts; service operations throw `UnsupportedPlatformException`, which `cli` maps to exit code 4. | `UnsupportedPlatform`, `UnsupportedServiceController`, `UnsupportedServiceHost` |
| `agent` (module `nyajsw-agent`) | Agent `premain` and the shared protocol. | `Agent`, `AgentProtocol` |

Design rules that keep this maintainable (see AGENTS.md §4.4): registries over `if/else` chains,
interfaces at the OS boundary, immutable config records, one place per concern.

### 4.4 Windows integration through JNA

* Dependencies: `net.java.dev.jna:jna` and `net.java.dev.jna:jna-platform` (latest stable 5.x;
  version recorded in the parent POM, decision D5).
* **Reused from `jna-platform`** (checked against its sources): `Advapi32` — `OpenSCManager`,
  `OpenService`, `CreateService`, `DeleteService`, `StartService`, `ControlService`,
  `QueryServiceStatusEx`, `ChangeServiceConfig2`, `QueryServiceConfig2`,
  `StartServiceCtrlDispatcher`, `RegisterServiceCtrlHandlerEx`, `SetServiceStatus`,
  `CloseServiceHandle`; `Winsvc` — `SERVICE_STATUS`, `SERVICE_STATUS_PROCESS`,
  `SERVICE_TABLE_ENTRY`, `SERVICE_MAIN_FUNCTION`, `HandlerEx`, `SERVICE_DESCRIPTION`,
  `SERVICE_FAILURE_ACTIONS`, constants; `Kernel32` — `OpenProcess`, `GenerateConsoleCtrlEvent`,
  `SetPriorityClass`, `SetProcessAffinityMask`, `SetConsoleTitle` (declared in `Wincon`, which
  `Kernel32` extends); `Kernel32Util.formatMessage` for error text.
  `Advapi32.INSTANCE` is loaded with `W32APIOptions.DEFAULT_OPTIONS`, so the Unicode entry points
  are used throughout.
* **Hand-written bindings** (`platform.windows.jna`), because `jna-platform` does not provide them:
  the `SERVICE_DELAYED_AUTO_START_INFO` and `SERVICE_PRESHUTDOWN_INFO` structures for
  `ChangeServiceConfig2`; job objects (`CreateJobObject`, `SetInformationJobObject` with
  `JOBOBJECT_EXTENDED_LIMIT_INFORMATION` and `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`,
  `AssignProcessToJobObject`, `TerminateJobObject`); `SetConsoleCtrlHandler` (backlog item); LSA
  functions to grant "Log on as a service" (`LsaOpenPolicy`,
  `LsaAddAccountRights`, `LsaClose`, `LsaNtStatusToWinError`). Every structure gets a layout test
  (`size()`, field order) and is used only through the `ScmApi` / `ProcessApi` facades.
* `ScmApi` (service control), `ServiceHostApi` (running as a service, P6.1), `LsaApi` (P5.5) and
  `ProcessApi` (job objects, `OpenProcess`, console functions) are **our** interfaces with one
  method per Win32 call we use. Their `Jna*` implementations are the only classes that read the
  last error (`Native.getLastError()`),
  wrapping it into `ServiceException` / `PlatformException` with the Win32 code and message. Tests
  mock the facades; JNA types never leave `platform.windows`. (`W32ServiceManager`/`W32Service` from `jna-platform` are not used: they
  cannot create or delete services, throw their own exception type and are not mockable — D5.)
* **Minimal access rights per command**: `-q` opens the SCM with `SC_MANAGER_CONNECT` and the
  service with `SERVICE_QUERY_STATUS` (plus `SERVICE_QUERY_CONFIG` once it shows configuration), so it works unelevated; install/remove
  use `SC_MANAGER_CREATE_SERVICE` / `DELETE`; start/stop use `SERVICE_START` or `SERVICE_STOP`,
  each with `SERVICE_QUERY_STATUS` to wait for the new state.
* **Service registration**: `CreateService(SERVICE_WIN32_OWN_PROCESS, start type, dependencies as a
  double-NUL-terminated list, account, password)`, then `ChangeServiceConfig2` for the description,
  delayed auto-start and the pre-shutdown timeout (the stop budget, `wrapper.shutdown.timeout` plus
  `wrapper.jvm_exit.timeout`, plus a margin for the final `terminate` step, its 10 s wait and the
  `STOPPED` report). It is always set explicitly: the Windows
  default is only 10 s since Windows 10 version 1703 (build 15063), 180 s before. When
  `wrapper.ntservice.account` names a real account (not `LocalSystem`, `LocalService` or
  `NetworkService`), it is granted `SeServiceLogonRight` through the LSA binding (as YAJSW/Tanuki
  do); without it the first start fails with 1069. Binary path, built by
  `ServiceCommandLine` (D12):

  ```
  "<java.home>\bin\javaw.exe" <wrapper.ntservice.additional.N …> -Djna.tmpdir=<wrapper.tmp.path> -cp "<java.class.path of the installing JVM>" io.github.jacobosanchez.nyajsw.cli.Main --service "<absolute wrapper.conf>"
  ```

  `java.home` and the default classpath (`java.class.path`) are those of the JVM running `-i`, so the
  command works from an unpacked build and from the distribution alike; callers may pass an explicit
  classpath. `wrapper.java.command` governs only the
  application JVM.
* **Running as the service** (`--service`): `Main` calls `StartServiceCtrlDispatcher` before
  anything else, so the SCM's 30-second start window is never at risk. The table entry name may be
  empty for `SERVICE_WIN32_OWN_PROCESS`; the real name arrives in `ServiceMain`'s `argv[0]`.
  The dispatcher runs on a daemon thread of its own (`nyajsw-service-dispatcher`), so the caller
  is not stuck when Windows would never release it (below).
  `ServiceMain` (a JNA callback thread, named `nyajsw-service` through `CallbackThreadInitializer`)
  registers the handler, reports `START_PENDING` with `dwControlsAccepted = 0` and a 10 s wait hint
  at once, then runs the service body (P6.2: configuration and logging, the supervisor), which
  reports `RUNNING` (accepting `STOP`, `SHUTDOWN`, `PRESHUTDOWN`) once readiness passes. It blocks
  until the body has ended and reports `STOPPED` last. The control handler reports `STOP_PENDING`
  synchronously with a 10 s wait hint, then tells the body, which queues the event to the
  supervisor and returns. Reports go one way: a `starting` after `running`, or either after a stop,
  is ignored. `INTERROGATE` returns `NO_ERROR` without a report, other controls
  `ERROR_CALL_NOT_IMPLEMENTED`. Since `START_PENDING` and `STOP_PENDING` accept no controls, Windows
  refuses a stop during the start with 1061, and sends no `SHUTDOWN`/`PRESHUTDOWN` to a service
  already stopping; the start must therefore stay bounded (P6.2). If the service thread cannot
  register its handler or report `STOPPED`, Windows keeps the dispatcher blocked and the SCM does
  not end the process, so `ServiceHost.run` returns by itself and throws why. Callback objects are
  kept in static fields so the native pointers stay valid (the one sanctioned static state,
  AGENTS.md §4.4). When the application cannot be kept running, the
  service ends with `dwWin32ExitCode = ERROR_SERVICE_SPECIFIC_ERROR` (1066) and the reason in
  `dwServiceSpecificExitCode`.
* **Pending states**: the checkpoint is incremented on every report (from 1 in each pending state)
  and the wait hint is at least twice the readiness interval with a 2-second floor, so long
  readiness checks never trip the SCM; the host enforces the floor (P6.1), P6.2 the rest.
  `PRESHUTDOWN` is accepted by default because plain `SHUTDOWN` only leaves the few seconds of
  `WaitToKillServiceTimeout`.
* **Error mapping** (`Win32Errors`): 5 `ERROR_ACCESS_DENIED` → "run as Administrator";
  1053 `ERROR_SERVICE_REQUEST_TIMEOUT`; 1056 `ERROR_SERVICE_ALREADY_RUNNING`;
  1057 `ERROR_INVALID_SERVICE_ACCOUNT`; 1060 `ERROR_SERVICE_DOES_NOT_EXIST`;
  1062 `ERROR_SERVICE_NOT_ACTIVE`; 1069 `ERROR_SERVICE_LOGON_FAILED` → logon-right hint;
  1063 `ERROR_FAILED_SERVICE_CONTROLLER_CONNECT` (`--service` run outside the SCM: use `-t` or
  `-c`, P6.1); 1072 `ERROR_SERVICE_MARKED_FOR_DELETE`; 1073 `ERROR_SERVICE_EXISTS`; 1332
  `ERROR_NONE_MAPPED`
  (no such account, P5.5); anything else through
  `Kernel32Util.formatMessage`.
* **Native library loading**: in service mode `jna.tmpdir` is baked into the binary path at install
  time, because JNA is already loaded when the configuration is read; in console/client mode it is
  set from `wrapper.tmp.path` before the first JNA call. Shipping `jnidispatch.dll` beside the jar
  with `jna.boot.library.path` is the documented fallback for locked-down hosts (R3).

### 4.5 Configuration engine

* **Parsing** (`ConfigSource` boundary, `FileConfigSource` for files, `ConfigLoader`
  orchestrates): line-oriented, `key=value` (split at the first `=`), `#` and `!` comment lines,
  blank lines ignored, include directives `#include <path>`, `#include.required <path>` and
  `include = <path>` (key case-insensitive) relative to the including file, falling back with a
  warning to the main file's directory (YAJSW's base) when nothing exists there. `${...}` in an
  include path is resolved by the `Interpolator` with the layer order of the whole configuration
  (a system property, else the last entry read before the include line, else a built-in default),
  then system properties and environment. An include path that reads a secret (a registry-secret
  key, or a system property or environment variable of such a name, directly or through another
  key) is refused without naming it, since the path would carry the secret into messages and entry
  locations; a secret under a name the registry cannot recognise (`${env:DB_PASS}`) is not detected. `include =` and
  `#include.required` are required: a missing file or an unresolved variable in the path → error
  naming file and line. A plain `#include` is optional, as in the Tanuki format (YAJSW treats it as a comment), so
  the same problems only warn; an include cycle is always an error. Encoding: UTF-8,
  BOM skipped; BOM + invalid UTF-8 → error; no BOM and invalid UTF-8 → windows-1252 with a warning;
  UTF-16 (BOM or NUL bytes) → error asking for UTF-8 or `-Dwrapper.conf.encoding` (D7).
  `wrapper.conf.encoding` replaces this detection with strict decoding in that charset (a byte that
  does not fit is an error at its line; a leading byte order mark is skipped; a UTF-8 one under
  another charset is an error). A system property wins (UTF-16 or EBCDIC can only be given that
  way; set empty, it forces the detection); otherwise a lenient probe of the file finds its own
  declaration (last one wins, no variables), which must be ASCII-compatible, and the file and its
  includes are read again with it and must still declare the same charset. A line ending in an
  odd number of `\` continues on the next line (as in YAJSW's Commons Configuration parser); blank
  and comment lines inside a continued value are skipped, as there. So `C:\app\` must be written
  `C:\app\\` or `C:/app/`; a continuation line that looks like a new `wrapper.*` entry gets a
  warning. In every value read from a file (and in `include =` paths) a backslash before `\`, `,`,
  `"`, `'`, a space or one of `# = : !` stands for that character, as in YAJSW's Commons
  Configuration, which the owner's Denodo files show (`-Djava.locale.providers=COMPAT\,SPI`,
  `C:/Denodo/9/VDP9.4b\\conf`, P1.9); every other backslash is literal: `\a` as in YAJSW, but also
  `\t`, `\n`, `\r`, `\f`, `\b` and `\u` + four hex digits, which YAJSW reads as control
  characters and Unicode escapes, so Windows paths such as `C:\temp` keep their meaning. Values from system
  properties and the environment are taken as they are, as in YAJSW; plain `#include` paths too,
  since the Tanuki format has no escapes. Path-typed keys then go through `ValueParser`'s tolerance
  for doubled backslashes (`\\` becomes `\`, except a leading `\\` followed by another character,
  a literal UNC or `\\?\` prefix). Entries keep file and line (`ConfigLocation`) for messages;
  problems that do not stop loading are `ConfigWarning`s. The treatment of `include` and the
  encoding default are still unconfirmed against YAJSW itself; see the D7 note and P1.9.
* **Interpolation** (`Interpolator`): `${name}` resolved against, in order, keys in the
  configuration itself (YAJSW lets any key act as a variable, e.g. `JAVA_HOME=…` then
  `${JAVA_HOME}`), wrapper JVM system properties, environment variables (case-insensitive on
  Windows, an exact name first); the explicit prefixes `${sys:name}` and `${env:NAME}` select one
  source. Values of configuration keys (including keys set by system properties, which are merged as
  entries) are resolved recursively; values read through the `sys:`/`env:` lookups, or for names no
  key has, are used as they are. Unresolved → left as-is plus a warning; a `${` without
  `}` → kept with a warning; cycles → error (a self-reference such as `JAVA_HOME=${JAVA_HOME}` points
  at the `env:` prefix); a reference that resolves to nothing and looks like code (parentheses,
  quotes, `;`, braces, brackets, or a leading `if`/`new`/`def` and a space) → error naming Groovy as
  unsupported, so names such as `${env:ProgramFiles(x86)}` still work; code around a nested
  reference (`${util.f("${x}")}`) is caught too, and so is YAJSW's `${keystore name}` (P1.10),
  which would otherwise end up as a password. Nested references (`${a${b}}`) resolve only the
  inner one, with a warning, and there is no escape syntax for a literal `${` (both unverified
  against YAJSW, P1.9). Interpolation runs on the merged entries (one per key, after layering,
  P1.5). Warnings never echo values; errors show the offending expression only for non-secret keys,
  and for secret keys omit the referenced names and the cycle chain too.
* **Layering** (`ConfigLoader`): built-in defaults ← file (+includes) ← wrapper JVM system
  properties (`-Dname=value` on the wrapper command line, as YAJSW allows: any system property
  overrides a key the file sets under the same name, but only `wrapper.*` properties add keys the
  file does not set, so JVM properties such as `java.home` stay plain `${...}` variables). Constant defaults of exact keys are a layer of their own; derived
  defaults are computed by the typed views, and no unsupported key may have a constant default.
  Default-layer entries were written by nobody, so unknown/planned/unsupported checks skip them. The
  result (`LoadedConfig`) has one entry per key with
  its `ConfigLayer`, then is interpolated. A key set twice in the file takes its last value with a
  warning naming both places (unverified against YAJSW, whose Commons Configuration parser may keep
  both as a list; P1.9). Order: file entries at the position of their winning definition, a
  system-property override in the place of the entry it overrides, other system properties next in
  name order, defaults last. Include paths resolve `${key}` with the same layer order.
* **Indexed and dynamic keys** (`KeyCollector`): `prefix.N` collected in index order, gaps
  allowed; an index is a number or a name, possibly dotted (`.3`, `.lib`, `.2.1`), as YAJSW's
  reference allows, and indices sort by `IndexOrder`: digit runs by value and before other text,
  which compares by character, so `1 < 5 < ab5 < ab12 < lib` (the reference's own example) and
  `2 < 2.1 < 2.10 < 3` (P1.11); the same index written twice (`.7`, `.07`) keeps both in file order
  with a warning; free
  placeholders (`wrapper.app.env.<variable>`) keep file order; a key belongs to the family the
  registry resolves it to, so the exact `wrapper.on_exit.0` is not a `wrapper.on_exit.<code>` member;
  `wrapper.app.env.<KEY>`, `wrapper.on_exit.<code>`, `wrapper.filter.trigger.N` handled by
  `KeySpec` patterns (`KeyPattern` templates: `<n>` matches an index (segments of letters, digits,
  `_` and `-`), `<code>` a signed exit code, any other `<label>` one or more non-empty segments; an
  exact name wins over a template, then more literal characters win, then index and exit-code
  placeholders over free ones; the registry rejects two equally specific templates that can match
  the same key, and declarations another one shadows). Because an index may be a name, a key such as
  `wrapper.java.additional.1.stripquotes` would be a member of `wrapper.java.additional.<n>`; the
  Tanuki keys of that shape (`*.stripquotes`, `wrapper.java.additional.auto_bits*`,
  `wrapper.java.classpath.use_environment`/`missing_log_level`,
  `wrapper.java.library.path.append_system_path`) are declared as unsupported so that their own,
  more specific declaration wins; they carry the `YAJSW` origin (`KeyOrigin` has no Tanuki value)
  and say "Tanuki" in their description. `IndexOrder` always puts a digit run before text; YAJSW's
  own comparator may differ for indices with `-` or `.` before digits and may not equate `07` and
  `7`, which matters for no real file seen so far.
* **Key registry** (`ConfigKeys`): every key declared once with name/pattern, type (string, int,
  duration-seconds, size, enum, path, boolean, list), default (a constant, or a derived default
  that depends on the resolved configuration or the environment, e.g. the stop-strategy and liveness
  defaults that depend on `wrapper.agent.enabled`; the registry holds its description and the typed
  view that owns the key computes it, D8), origin (`YAJSW`/`NYAJSW`), `KeyStatus` (§3.1), secret flag,
  category (for grouping in the generated document), description, the phase/notes, and which parts
  of a YAJSW declaration are still unverified (`Unverified`: name, default, values, meaning; R11).
  Used for: typed access,
  validation with aggregated errors, unknown/unsupported warnings, secret masking, and **generating
  `docs/COMPATIBILITY.md`** (a test fails when the committed document is stale; running it with
  `-Dnyajsw.docs.update=true` rewrites the document).
* **Validation** (`ConfigValidator`, P1.6a): unknown `wrapper.*` keys warn (other unknown keys are
  plain variables); planned, ignored and unsupported keys warn once per declaration; fatal keys with
  a value (empty ones only warn) and values of supported keys that do not fit their type are errors,
  all reported together in configuration order (`ConfigException.aggregate`). A planned key has no
  effect yet, so a value that does not fit only warns. Types (`ValueParser`, also used to check the
  registry's constant defaults): int; boolean also as `yes/no/on/off`, as Commons Configuration
  accepts; non-negative seconds; sizes with binary `K/M/G`; enum values and aliases in any case;
  comma lists. A value that leaves the key unset (`ValueParser.isUnset`: empty for non-string keys,
  no list items) is not checked; default-layer entries are not judged; a secret set by a system
  property warns, known key or not. `SecretMasker` hides secret
  values wherever they appear, including copies made by `${...}`, and copies of secrets read from
  system properties or environment variables under secret names (`LoadedConfig.externalSecrets`,
  held as `Secret`s). A secret under a name the registry cannot recognise (`${env:DB_PASS}`) is not
  detected. `ConfigDump` is the only sanctioned rendering of values.
* **Typed views** (P1.6b): `WrapperConfig.from(LoadedConfig)` validates and exposes `JavaConfig`,
  `ServiceConfig`, `LogConfig`, `LifecycleConfig`, `ProbeConfig` records. Nothing outside `config`
  reads raw strings. A value that is unset or does not fit falls back to the constant default;
  derived defaults are computed here (working directory = configuration directory, service name =
  simple name of the main class, stop strategies and liveness from `wrapper.agent.enabled` once the
  agent is implemented; while that key is only planned it has no effect, so the defaults are
  `terminate` and no liveness probe, as P3.4 expects), except
  the Java executable, whose default depends on the platform and is resolved in P2.2 (a bare name
  such as `java` stays for a `PATH` lookup). Paths are absolute: the working directory against the
  configuration directory, application-side paths (jar, library path, image, a command with a
  directory, and classpath entries in P2.3) against the working directory, wrapper-side paths
  (log file, pid files, temporary directory, probe files) against the configuration directory
  (D13); classpath entries stay text for wildcard expansion. An empty text value counts as not set
  (`ConfigValues.text`), so derived defaults apply; list families skip empty members. Account names
  undo doubled backslashes like paths (`DOMAIN\\svc`). Exit codes: codes 2^31..2^32-1 map to the
  negative `int` Java reports, then one action per code, the last spelling in file order winning
  (a system property counting as the latest; spellings that agree only after that mapping, `-1`
  and `4294967295`, warn; a later empty or invalid spelling leaves the default); code 0
  only from `wrapper.on_exit.0`. Odd but harmless input (other spellings of 0, environment names
  that differ only in case) always warns; a value problem found here (invalid path, code out of
  range) is an error once the key takes effect (`KeyStatus.takesEffect`), a warning before. Passwords are
  `Secret`s and the records' `toString()` hides values, so only `ConfigDump` shows them.
* Relative paths resolve against the configuration file's directory unless the key says otherwise
  (D13).

### 4.6 Lifecycle, restart and stop semantics

State machine in `Supervisor`: `IDLE → STARTING → (readiness) → RUNNING → STOPPING → STOPPED`,
with `RESTART_WAIT` between invocations and `FAILED` when the restart policy gives up. `IDLE` is
also where the wrapper waits after a `STOP` exit action. The allowed moves are listed once, in
`State`; the supervisor processes one event at a time on its own thread, timers come from a
`Scheduler`, so every transition is tested with a fake clock (P3.1).

* **Exit handling**: `wrapper.on_exit.<code>` / `wrapper.on_exit.default` → `RESTART`,
  `SHUTDOWN` (stop application and wrapper/service) or `STOP` (stop the application, keep the
  wrapper alive). Default, as YAJSW documents it: `SHUTDOWN` (`wrapper.on_exit.default`), so an
  application that exits with an error is not restarted unless the file asks for it; the default
  covers every code, 0 included, unless `wrapper.on_exit.<code>` (or the exact
  `wrapper.on_exit.0`) sets one. Whether the wrapper keeps running after `STOP` depends on
  `wrapper.control` (D20): only under `LOOSE` and `APPLICATION`, where the wrapper survives the
  application; under `TIGHT` (the default) and `WRAPPER` the wrapper ends with it, as for
  `SHUTDOWN`.
  Exit actions are **not** evaluated while the supervisor is `STOPPING`: an application that exits
  because we asked it to is never restarted.
* **Restart policy**: `wrapper.restart.delay` (default 5 s), `wrapper.max_failed_invocations`
  (default 5, minimum 1), `wrapper.successful_invocation_time` (default 60 s), all as YAJSW
  documents them; `wrapper.startup.delay` (default 0) delays the first start. An invocation that
  cannot be started, or exits (or fails readiness) before the successful-invocation time, counts as
  failed; the counter resets after a successful one. A launch error is retried whatever the exit
  actions say. Reaching the limit → `FAILED` → the service reports `STOPPED` with
  `ERROR_SERVICE_SPECIFIC_ERROR`. Note that SCM recovery (failure actions) ignores a service that
  reports `STOPPED` unless `SERVICE_CONFIG_FAILURE_ACTIONS_FLAG` is set; nyajsw sets that flag
  whenever failure actions are configured (P9) and the migration guide explains the interplay (R6).
* **Startup**: `wrapper.startup.timeout` (default 30 s, 0 for no limit) bounds readiness. On
  timeout the invocation is treated as failed (whatever its run time), the application is
  terminated and the restart policy applies (P3.3).
* **Liveness**: while `RUNNING`, `wrapper.liveness.failures` failed checks in a row terminate the
  application at once; `wrapper.liveness.action=RESTART` then restarts it under the restart
  policy, `SHUTDOWN` ends supervision `FAILED` (P7.1, D24).
* **Stop sequence**: an ordered chain of `StopStrategy` instances from `StopStrategyRegistry`,
  selected by `wrapper.stop.strategy`. The registry and the `terminate` strategy exist from P3;
  P7 registers `agent` and changes the default to `agent,terminate` whenever
  `wrapper.agent.enabled=true` (default recorded in the key registry, never hard-coded elsewhere).
  1. `agent` — send `STOP` over the agent channel; the application JVM runs `System.exit(0)` and
     therefore its shutdown hooks.
  2. `command` — run `wrapper.stop.command` (later also a `wrapper.stop.conf` Java program).
  3. `console` (backlog) — `GenerateConsoleCtrlEvent` on the shared console, with the wrapper
     shielded by `SetConsoleCtrlHandler`.
  4. `terminate` — always last, after `wrapper.shutdown.timeout` (default 30 s; YAJSW documents no
     default and bounds the stop by `wrapper.jvm_exit.timeout` plus `wrapper.shutdown.timeout`;
     P3.4 adds the former to the budget) is exhausted:
     kills the whole process tree (`TerminateJobObject` when a job object exists, otherwise
     `ProcessHandle.descendants()` + `destroyForcibly()`), because on Windows `Process.destroy()`
     is already `TerminateProcess` and leaves grandchildren (e.g. a wrapped `.bat`) alive.
  Each graceful strategy gets an equal share of the timeout (with none, the whole timeout is
  waited); the chain is configurable and extensible (P3.4).
* **Coupling** (`wrapper.control`, YAJSW default `TIGHT`): `TIGHT` — wrapper exit stops the
  application and application end (after exit actions) stops the wrapper; `WRAPPER` — the wrapper
  ends when the application ends, but the application survives the wrapper; `APPLICATION` — the
  application ends when the wrapper ends, but the wrapper survives the application; `LOOSE` —
  independent (meanings as YAJSW documents them, P1.10). The supervisor honours both directions
  (P3.2, D20): under `LOOSE`/`WRAPPER` a stop request ends supervision and leaves the application
  running, logged with its pid. On Windows a job object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`
  enforces `TIGHT`/`APPLICATION` even if the wrapper dies abruptly: the job is created when
  supervision begins, each invocation is assigned right after start
  (`OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE)`), the job is closed when supervision
  ends, which ends whatever the application started, also after a clean exit of its own, and the
  short pre-assignment window is accepted and documented (P6.4).
* **Pid files**: `wrapper.pidfile` (wrapper) and `wrapper.java.pidfile` (application), written on
  start, removed on stop, stale files overwritten.
* **Console mode**: Ctrl+C reaches both the wrapper and the child because they share the console.
  The wrapper's shutdown hook asks the supervisor to stop, which runs the stop chain: by default
  the agent ends the child (P7.3); when no graceful step can ask (no agent connected) the stop
  budget is still waited for the child to finish its own shutdown, then it is killed. The exit
  code returned is the application's.

### 4.7 Readiness and liveness probes (alternative start validation)

Motivation: with YAJSW "started" mostly means "the JVM process exists". nyajsw lets the operator
choose what started means and reports `RUNNING` to the SCM only then. The same probe types can be
used as liveness checks to trigger restarts.

`Probe` SPI: `ProbeResult check(ProbeContext ctx)` with access to the process handle, captured
output, configuration and a clock; probes are stateless or own their state explicitly. Registered in
`ProbeRegistry` by type name; configuration selects them by name. The SPI, registry and
`ProcessProbe` exist from P3 so later probes are additions, not rewrites.

| Type | Readiness means… | Configuration |
|---|---|---|
| `process` (default) | The application process is alive | — |
| `tcp` | A TCP connection to host:port succeeds (5 s connect timeout) | `wrapper.readiness.tcp.host` (default `127.0.0.1`), `wrapper.readiness.tcp.port` |
| `http` | A `GET` returns an expected status (redirects not followed) | `wrapper.readiness.http.url`, `.status` (default `200-399`), `.timeout` (default 5 s) |
| `log` | A line of the captured stdout/stderr (or one appended to a file) since the previous check matches a regex | `wrapper.readiness.log.pattern`, `.file` (optional, relative to `wrapper.working.dir`) |
| `file` | A file or directory exists (relative to `wrapper.working.dir`) | `wrapper.readiness.file.path` |
| `command` | An external command exits `0`, run without a shell in the working directory | `wrapper.readiness.command`, `.timeout` (default 10 s) |
| `agent` | The in-JVM agent connected and answers `PING` | `wrapper.agent.enabled` (default `true`) |
| `jmx` (backlog) | A JMX attribute matches | `wrapper.readiness.jmx.url`, `.attribute`, `.value` |

* `wrapper.readiness.type` is a comma-separated list; **all** listed probes must pass. The
  per-probe settings (`wrapper.readiness.<type>.*`) serve `wrapper.liveness.type` as well (D25).
  `wrapper.readiness.interval` (default 1 s) is the polling cadence; `wrapper.startup.timeout` is
  the overall bound. While pending, the service host bumps the SCM checkpoint on every report and
  uses a wait hint of at least twice the interval (§4.4).
* Liveness: `wrapper.liveness.type` (default `agent` when the agent is enabled, mirroring YAJSW's
  default pinging, documented as `wrapper.ping.interval` 5 s and `wrapper.ping.timeout` 30 s;
  otherwise none, so only process exit is observed), `wrapper.liveness.interval`
  (default 10 s), `wrapper.liveness.failures` (consecutive failures before acting, default 3),
  `wrapper.liveness.action` (`RESTART` default, or `SHUTDOWN`). `wrapper.ping.interval` /
  `wrapper.ping.timeout` from YAJSW map onto the `agent` liveness probe (D16).
* Agent channel: the wrapper listens on a loopback TCP port chosen at launch; the port is passed to
  the child as `-Dnyajsw.agent.port`, the random token as the environment variable
  `NYAJSW_AGENT_TOKEN` so it never appears in a logged command line. Protocol (`AgentProtocol`,
  defined once in `nyajsw-agent`, details in P7.3a): `HELLO 1 <token>`, `PING <n>`/`PONG <n>`,
  `STOP`/`STOPPING`, `UNKNOWN`; line-based ASCII over `127.0.0.1` on both ends, tested in-process
  with both ends. The agent never blocks application startup: `premain` returns
  immediately and works on a daemon thread.

### 4.8 Logging

* `WrapperLog` is a thin facade over `java.util.logging` (decision D6: zero dependencies, full
  control over format and rotation). Levels map to YAJSW names: `NONE FATAL ERROR STATUS INFO DEBUG`.
  The facade (with a console sink) arrives in P2 because the launcher already needs a log sink; the
  rolling file handler and formats follow in P4.
* `wrapper.logfile` with `ROLLNUM` and `YYYYMMDD` tokens (`YYYYMMDD` required for rollmode `DATE`);
  `wrapper.logfile.maxsize` (YAJSW: bytes or `k`/`m` suffixes, `0` = no size rolling, the
  default), `wrapper.logfile.maxfiles` (per day under `DATE`), `wrapper.logfile.maxdays` (`DATE`
  only, `-1` = keep all), `wrapper.logfile.rollmode`, `wrapper.logfile.loglevel` and
  `wrapper.console.loglevel` (YAJSW default `INFO` for both), `wrapper.logfile.format` (default
  `LPTM`, blank disables file logging), `wrapper.console.format` (default `PM`, blank disables
  console output). YAJSW describes rollmodes `JVM` (roll at wrapper start and before each restarted
  JVM) and `DATE`, and names `WRAPPER` without describing it; the registry declares these three
  (`RollMode`; nyajsw reads `WRAPPER` as "roll at wrapper start" only). Rolling by `maxsize`
  applies in every mode, although the reference says `JVM` files have no size limit (§3.4), and
  Tanuki's `SIZE`, `NONE`, `SIZE_OR_JVM` and `SIZE_OR_WRAPPER` are accepted (D22, P4.3b). Default file: `wrapper.log` beside the
  configuration file (YAJSW: beside the wrapper, since 11.01 in its `log` folder; §3.4).
* Format tokens follow the Tanuki/YAJSW letter convention (`L` level, `P` prefix, `D` thread,
  `T` time, `Z` millisecond time, `M` message) with `wrapper` / `jvm N` prefixes so operators
  can tell wrapper lines from captured application output. Fields are joined by ` | `; times are
  local, `yyyy/MM/dd HH:mm:ss` (`.SSS` for `Z`), a format of nyajsw's own (P4.2).
* Application stdout/stderr are captured by `OutputPump` and logged at `INFO`; the command line
  makes the application write them in UTF-8 (`-Dstdout.encoding`/`-Dsun.stdout.encoding` and the
  `stderr` pair, read by Java 18+ and 8–17 respectively); a value written in the JVM options under
  either name is used instead, and the chosen charset is given under both names after the JVM
  options, so the application writes exactly what the wrapper decodes (P2.4); the same stream feeds the `log` probe and (backlog)
  `wrapper.filter.trigger.N`. The command line is logged through the masking form of
  `JavaCommandBuilder`, so secrets never reach the log.
* `wrapper.debug=true` raises the wrapper log level to `DEBUG`, and `wrapper.debug.level` (0–3,
  default 3) chooses which debug messages are written by the detail each one names (P4.1). In
  service mode nothing is written to the console; logging initialises to the file as early as the
  configuration allows, with a bootstrap fallback (`nyajsw-bootstrap-<name>.log` beside the
  configuration file since P6.3; `%TEMP%` only when that path cannot be parsed) for errors
  before the configuration is read and for the problems of the log file (P4.3a).

### 4.9 Command-line interface

`cli.Main` parses the switches of §3.2, loads configuration (reporting all errors at once), picks the
`Platform`, executes one `Command` and maps the outcome to an exit code. Everything is plain Java;
no CLI framework. `--service` is the only path that touches `ServiceHost`.

## 5. Phases

Each item is one reviewed commit (see AGENTS.md §4.2). Items may be split further; do not merge them.
Items marked *(recommended)* were not requested by the owner and may be dropped.

### P0 — Project skeleton
Goal: a building, testing, CI-verified empty project.
- [x] P0.1 Parent `pom.xml` plus the three modules in one commit (a parent listing modules that do
      not exist does not build): groupId `io.github.jacobosanchez.nyajsw`, `release` 21 for
      wrapper/dist, `release` 8 with `testRelease` 21 and `-Xlint:-options` for the agent, UTF-8,
      dependency and plugin management (compiler, surefire with the TestNG provider, enforcer for
      Java 21 + Maven 3.9), versions in properties, one smoke TestNG test per module, Mockito wired
      with `-javaagent:<mockito-core>` in the surefire `argLine` to avoid the JDK 21 dynamic-agent
      warning, `MockitoAnnotations.openMocks` in `@BeforeMethod` (no `mockito-testng`).
- [x] P0.2 `.gitignore`, `.editorconfig`, `.gitattributes` (LF, CRLF for `.bat`, byte-exact config
      fixtures), short `README.md` linking to this plan and AGENTS.md.
- [ ] P0.3 *(recommended)* GitHub Actions: `ubuntu-latest` runs `mvn -q verify`; `windows-latest`
      runs `mvn -q verify -Pwindows-integration` (hosted Windows runners are elevated — confirm in
      the first run). TestNG group `windows-integration` excluded unless the profile is active.
- [x] P0.4 Record chosen versions (JNA, TestNG, Mockito, plugins) in §9 D4/D5 (build plugins in D3).
Done when: `mvn -q verify` is green on Linux and Windows.

### P1 — Configuration engine
Goal: load any YAJSW `wrapper.conf` into a typed, validated model with warnings.
- [x] P1.1 `ConfigSource` line parser: `#`/`!` comments, trailing-backslash continuation with the
      `C:\app\\` fixture, `#include` and `include =`, encoding/BOM, literal backslashes (D7).
- [x] P1.2 `ConfigKeys` registry + `KeySpec`/`KeyStatus` (patterns, types, defaults, origin, status,
      secret flag). Every §7 and §4.7 key is declared with the status seeded from §7, plus
      `wrapper.conf.encoding` (P1.9) and an exact `wrapper.on_exit.0` that carries the `SHUTDOWN`
      default for a clean exit; YAJSW defaults the plan has not confirmed are left undeclared or
      marked `Unverified.DEFAULT` (R11). Secret detection also treats any key ending in `.password`
      as secret, known or not.
- [x] P1.3 `Interpolator`: config/system-property/environment resolution, `sys:`/`env:` prefixes,
      unresolved warnings, cycle detection, Groovy detection. Also interpolate include paths (YAJSW
      does), through an `Interpolator` passed to `FileConfigSource` (defaulting to this process's
      system properties and environment).
- [x] P1.4 Indexed (`.N`) and dynamic (`wrapper.app.env.*`, `wrapper.on_exit.*`) key collection
      (`KeyCollector`, with placeholder capture in `KeyPattern`).
- [x] P1.5 Layering/precedence (defaults, file, system properties) and `ConfigLoader` entry point;
      one entry per key before `Interpolator.interpolate` ("last definition wins", as for include
      paths), include paths resolved with the same layer order (`FileConfigSource.Layers`), and the
      documented entry order `KeyCollector` relies on.
- [x] P1.6a Validation with aggregated `ConfigException`; unknown / planned / ignored / unsupported /
      fatal key handling (default-layer entries skipped); value types (`ValueParser`); secret set
      by a system property warns; secret masking in dumps, including values copied by `${...}`
      (`SecretMasker`, `ConfigDump`).
- [x] P1.6b Typed `WrapperConfig` records (`JavaConfig`, `ServiceConfig`, `LogConfig`,
      `LifecycleConfig`, `ProbeConfig`) built from a validated `LoadedConfig`, with derived defaults
      and relative paths resolved against the configuration directory (D13). Decide per family
      what a repeated index means (lists keep both; exit codes and filter triggers need one value
      per index, joined on the normalised index, P1.11), and warn when a
      `wrapper.on_exit.<code>` member is 0 written differently (`00`, `-0`), since the exact
      `wrapper.on_exit.0` carries the default. (Filter triggers, which pair `trigger.<n>` with
      `action.<n>` by number, are unsupported until P9 and get their typed view then.)
- [x] P1.7 `docs/COMPATIBILITY.md` generator (`CompatibilityDocument`) + staleness test
      (`CompatibilityDocTest`; `-Dnyajsw.docs.update=true` rewrites); the hand-written §7 tables are
      retired.
- [x] P1.8 Fixtures: a YAJSW-style default template written from scratch, plus the owner's
      anonymised real configurations as soon as they are provided (§10 Q1). **The first real one**
      is `fixtures/denodo-vdp` (the owner's Denodo Virtual DataPort 9.4 service, byte for byte; no
      secrets), with a golden command-line test (`launch.DenodoFixtureTest`), and
      `fixtures/denodo-scheduler` (the Scheduler 9.4 service, `launch.DenodoSchedulerFixtureTest`).
      The template is
      `src/test/resources/conf/fixtures/yajsw-style/conf/wrapper.conf` (CRLF, doubled backslashes,
      an include, a continued value, forward-referenced variables, commented-out keys, trailing
      blanks, quotes and spaces in a JVM option, an account and a password, planned, ignored,
      unsupported and empty fatal keys). Each fixture directory mirrors an installation and has a
      `fixture.properties` manifest naming its main file (e.g. `conf/vdp/service.conf`) and its
      environment and system properties; `FixtureConfigurationsTest` loads every directory and fails
      on one without a manifest, so adding a real configuration means adding a directory with a
      manifest.
- [~] P1.9 D7 follow-ups once real fixtures exist: honour `wrapper.conf.encoding` (**done**, see
      §4.5 Parsing); settle backslash escaping (**done** with the owner's Denodo files: YAJSW's
      character escapes are undone in every file value, see §4.5; strictly they show what Denodo
      writes for YAJSW 13.x, and `\,` only means a comma when YAJSW enables commons-configuration's
      list delimiter, so the version in 13.18's `lib/` folder is still worth a look; the VDP file
      also showed that a library path entry may list several paths,
      `wrapper.java.library.path.2=${path}`, now split on `;`); `include`
      handling and case of environment names against a real file; check how
      YAJSW treats an index written twice (`.7` and `.07`; nyajsw keeps both with a warning, P1.4)
      and a key set twice (nyajsw: last value wins with a warning, P1.5; YAJSW's Commons
      Configuration probably keeps a list whose first value scalar getters return, i.e. first wins);
      note that nyajsw's constant defaults are visible to `${...}`, which YAJSW's in-code defaults
      probably are not; confirm the accepted boolean spellings (P1.6a accepts `yes/no/on/off`) and
      the doubled-backslash rule for paths, and whether numbers may be written in hex (`0x10`,
      rejected today), and whether YAJSW skips empty list members such as `wrapper.app.parameter.2=`
      (P1.6b skips them), whether account names really need doubled backslashes (P1.6b undoes them),
      and the path bases of D13; check files converted from Tanuki, where
      `wrapper.java.mainclass=org.tanukisoftware.wrapper.WrapperSimpleApp` takes the real main class
      from `wrapper.app.parameter.1` (nyajsw ignores `wrapper.java.mainclass`, which would then run
      the wrong class, and `WrapperStartStopApp` files, whose second parameter is an argument
      count the application would then receive; YAJSW's migration guide says that without `wrapper.java.app.mainclass` it
      takes the first application argument as the main class, so P2.2 should do the same); YAJSW
      14.01 parses with commons-configuration2 2.15.1 (13.18 is the reference, §10 Q9; its version
      is still to be read from its `lib/` folder), and its migration guide tells users to
      double every backslash (or use `/`) and to escape commas as `\,`, as the Denodo file does;
      what YAJSW does with an *unescaped* comma (split into a list, and then use which part?) is
      still open, nyajsw keeps the comma; quoted values of path keys (`wrapper.java.command="C:\Program
      Files\...\java.exe"`) are not unquoted, so `Path.of` rejects them with a warning.
- [x] P1.10 Apply the YAJSW reference check of 2026-11-06 (§11) to the registry and regenerate
      `docs/COMPATIBILITY.md`. Defaults that differ from the registry: `wrapper.on_exit.default`
      `SHUTDOWN` (registry `RESTART`; §3.3 says defaults match YAJSW where known, so keeping
      `RESTART` needs a §3.4 entry; the exact `wrapper.on_exit.0` default, which YAJSW does not
      document separately, is decided with it), `wrapper.successful_invocation_time` 60 s
      (registry 300 s). Defaults to add: `wrapper.restart.delay` 5 s, `wrapper.ping.interval` 5 s,
      `wrapper.ping.timeout` 30 s, `wrapper.logfile.loglevel` and `wrapper.console.loglevel`
      `INFO`, `wrapper.logfile.format` `LPTM`, `wrapper.console.format` `PM`,
      `wrapper.logfile.maxsize` `0`, `wrapper.restart.reload_configuration` `false`,
      `wrapper.console.pipestreams` `false` (Java applications), `wrapper.exit_on_main_terminate`
      `-1`, `wrapper.exit_on_main_exception` `999`. Values: `wrapper.logfile.rollmode` is `JVM`,
      `DATE` (and an undescribed `WRAPPER`), not `NONE`/`SIZE`/`SIZE_OR_DATE`; YAJSW documents
      `wrapper.ntservice.starttype` without `DISABLED` and `DELAY_START` (keep both as accepted
      extras). `Unverified` marks to clear (confirmed): `wrapper.control` default `TIGHT`, the
      names `wrapper.ping.interval`, `wrapper.restart.reload_configuration`,
      `wrapper.exit_on_main_terminate`, `wrapper.exit_on_main_exception`,
      `wrapper.console.pipestreams`, `wrapper.java.monitor.<setting>` (`deadlock`, `heap`, `gc`
      with `.interval` and `.restart`, plus `heap.threshold.percent` and `gc.threshold`),
      `wrapper.syslog.<setting>` (`facility`, `ident`, `loglevel`),
      `wrapper.ntservice.failure_actions.<setting>` (`reset_period`, `reboot_msg`, `command`,
      `actions`, `actions_delay`). Still undocumented by YAJSW (keep marked):
      `wrapper.java.command.javaw` default, `wrapper.working.dir` default, `wrapper.ntservice.name`
      default, `wrapper.shutdown.timeout` default. `wrapper.java.debug` is not a YAJSW key (only
      `wrapper.java.debug.port` is): change its origin or drop it (and P4.1 with it).
      `wrapper.filter.action.<n>` is a YAJSW key (values `RESTART`, `SHUTDOWN`, `NONE`, default
      `RESTART`), not a nyajsw extension; `FailureAction` needs `NONE`. YAJSW keys that overlap
      nyajsw designs: `wrapper.ntservice.additional.<n>` already means options of the service's
      wrapper JVM, so it should replace (or alias) the `wrapper.ntservice.java.additional.<n>`
      extension (AGENTS.md §4.6; D12, §4.4, P5.3); `wrapper.ntservice.java.command` selects the
      service JVM (default: the application's; D12 uses the installing JVM);
      `wrapper.ntservice.autoreport.startup` (default `true`: report `RUNNING` at once) is YAJSW's
      switch for delayed start reporting (§4.7), with `wrapper.ntservice.autoreport.waitready`;
      `wrapper.ntservice.reduce_signals` (default `true`) adds `-Xrs` to the application JVM in
      service mode, which P2.2 must reproduce; `wrapper.save_interpolated` (default `true`) makes
      YAJSW store the `${...}` values resolved at install time for the service, while D12
      re-reads and re-resolves the file at every start; `wrapper.startup.delay` (default 0)
      delays the first start. Keys to declare (at least as unsupported or ignored, so warnings
      name them): `wrapper.jvm_exit.timeout`, `wrapper.exit_code.stop`/`kill`/
      `fatal` (defaults 0, 999, 999), `wrapper.debug.level` (default 3), `wrapper.stopper`,
      `wrapper.java.debug.port`, `wrapper.java.command.64bit`, `wrapper.java.command.loglevel`,
      `wrapper.java.library.path.append_system_path`, `wrapper.java.statusfile`,
      `wrapper.java.idfile`, `wrapper.java.jmx`, `wrapper.*.umask`, `wrapper.app.status.log.lines`,
      `wrapper.app.pre.script`, `wrapper.app.pre_main.script`, `wrapper.restart.delay.script`
      (`.args`), `wrapper.restart.reload_cache`, `wrapper.ping.interval.logged`,
      `wrapper.logfile.maxdays`, `wrapper.logfile.desc`, `wrapper.logfile.inactivity.timeout`,
      `wrapper.console.minimized`, `wrapper.console.flush`, `wrapper.console.use_interpolated`,
      the other `wrapper.ntservice.*` keys (`logon_active_session`, `desktop`,
      `java.customProcName`, `password.prompt`, `password.prompt.mask`, `pausable`,
      `pausable.stop_jvm`, `process_priority`, `hide_console`, `load_order_group`, `console`),
      `wrapper.filter.trigger-regex.<n>`, `wrapper.filter.script.<n>.timeout`/`.maxConcInvoc`,
      `wrapper.filter.missing.*`, `wrapper.filter.debug.default`, `wrapper.script.<n>.<state>`
      (`.args`, `.timeout`), `wrapper.script.reload`, `wrapper.script.encoding`,
      `wrapper.timer.*`, `wrapper.config.script.<n>` (Groovy, e.g. password decryptors),
      `wrapper.condition.script` (`.args`), `wrapper.condition.cycle`, `wrapper.lockfile`,
      `wrapper.statusfile`, `wrapper.single_invocation`, `wrapper.cleanup_tmp`, `wrapper.port`
      (`.min`, `.max`), `wrapper.jvm.port*`, `wrapper.windows.cluster.*`, `wrapper.monitor.<n>.*`,
      `wrapper.log.encoding`, `wrapper.posix_spawn`, `wrapper.fork_hack`,
      `wrapper.disable_restarts`. Note: the reference gives `/tmp` as the `wrapper.tmp.path`
      default, but the 11.01 change log says it defaults to `jna.tmpdir` with files under the
      installation's `tmp` folder (keep it `Unverified`; it feeds D12's `-Djna.tmpdir`), and the
      reference also spells `wrapper.java.initialmemory.relative` (the registry has
      `wrapper.java.initmemory.relative`; accept both). YAJSW 12.04+ reads secrets through
      `${keystore <key>}`; the `Interpolator` should reject it as unsupported, as it does Groovy,
      instead of passing the text on as a password. Also fix the swapped `APPLICATION`/`WRAPPER`
      Javadoc in `ControlMode` (§4.6). AGENTS.md §4.6 calls `DELAY_START` a documented alias;
      it is an accepted one (YAJSW does not document it).
      **Done (registry):** the defaults, values, origins and marks above; `FilterAction` and the
      YAJSW `RollMode` values; `wrapper.ntservice.additional.<n>` replaces the extension (never
      released, so no alias); `wrapper.java.debug` dropped and `wrapper.debug.level` declared;
      `wrapper.update_rc` renamed `wrapper.daemon.update_rc` as the reference spells it;
      `wrapper.tray.port`, absent from the reference, marked `Unverified.NAME`; key templates
      accept `-` (`trigger-regex`); the missing keys declared per D18 (the reference's own
      colour-coded table of Tanuki keys tells which ones YAJSW supports); `wrapper.config.script.*`
      fatal, since ignoring a decryptor would pass encrypted values on, and so are
      `wrapper.condition.script` and `wrapper.windows.cluster.*` (ignoring them would run the
      application where or when it must not); `wrapper.on_exit.0` lost its own default, so code 0
      follows `wrapper.on_exit.default` as YAJSW documents; `wrapper.console.pipestreams` and
      `wrapper.save_interpolated` are ignored with a note on how nyajsw behaves; the new
      deliberate differences are in §3.4. **Done (interpolation):** an unresolved
      `${keystore name}` is an error, like Groovy, and omits the name for secret keys.
- [x] P1.11 String indices: YAJSW's reference allows `<n>` to be a number or a string, sorted
      alphanumerically (`wrapper.java.classpath.lib=lib/*/*.jar`, `wrapper.filter.trigger.exception`
      with `wrapper.filter.action.exception`). `KeyPattern` matches `<n>` as digits only, so such
      keys are unknown and their values dropped with a warning. Accept string indices in
      `KeyPattern`/`KeyCollector`, define the order of mixed numeric and string indices (check
      the reference's example), and keep `<code>` numeric. Needed before P2.2/P2.3. **Done:** see
      §4.5 "Indexed and dynamic keys"; `KeyCollector.Member.numbers()` now holds exit codes only.
Done when: every fixture present in the repository loads; every key of the former §7 matrix
(`ConfigKeysTest.seededKeys`) has a registry entry and a test.

### P2 — Application launcher
Goal: build and run the application command line exactly as the configuration says.
- [x] P2.1 `WrapperLog` facade with `LogLevel` and a console sink (file handler comes in P4).
      Instances are passed around, never looked up statically, and are immutable: P4 builds a
      bootstrap log (P4.3a) and then a new configured one, it does not reconfigure in place. Each sits on
      an anonymous `java.util.logging` logger, so no global logging configuration (nor
      `LogManager.reset()`) reaches it. Every sink has its own threshold (console now, the file's
      in P4) and the logger passes what the most verbose one wants. `forSource("jvm 1")` gives a
      view for captured application output (P2.4) on the same sinks. The console sink writes to
      standard output (YAJSW's console), never closes it, and reports formatting failures to its
      error manager instead of the caller; lines use YAJSW's default console format `PM` (source
      padded, `|`, message) plus any stack trace until `wrapper.console.format` arrives (P4.2).
- [x] P2.2 `JavaCommandBuilder`: `wrapper.java.command` resolution (default: `java`/`javaw` of the
      wrapper's `java.home`; `wrapper.java.command.javaw`), `-Xms/-Xmx` from
      `wrapper.java.initmemory/maxmemory`, `wrapper.java.additional.N`, `-Djava.library.path` from
      `wrapper.java.library.path.N`, main class vs `wrapper.java.app.jar`, `wrapper.app.parameter.N`;
      quoting policy for Windows (`ProcessBuilder` re-quotes arguments and rejects embedded quotes
      unless `jdk.lang.Process.allowAmbiguousCommands` is set — decide, document, test values such
      as `-Dx="a b"`); golden tests compare argument arrays, not joined strings; masking loggable form.
      **Done:** order executable, `-Xms`/`-Xmx` and `-Djava.library.path` (before the JVM options,
      so a value written there wins, as YAJSW documents for `-Xmx`), JVM options (empty ones skipped
      with a warning: `java.exe` would take them for the main class), `-classpath` + main class or
      `-jar` + jar, program arguments; with a jar, a main class and a classpath are ignored with
      warnings, and so is `javaw` with an explicit command or off Windows; without main class and
      jar the first program argument is the main class (YAJSW; an empty one is an error); quoting
      per D19. Secrets are also recognised without their quotes and with backslashes undone, so a
      quoted password copied into an option stays masked. The P2 keys stay *planned* in the
      registry until console mode runs the application (P2.5) and become *supported* then.
      `JavaCommand.loggable()` (also its `toString()`) masks secrets through the `SecretMasker` that
      `WrapperConfig.secrets()` now carries. Text values keep doubled backslashes until P1.9
      settles escaping, so `-Dx=C:\\dir` reaches the JVM with both backslashes, which Windows
      paths tolerate. `-Xrs` (P6.2) and the agent (P7.3) are added by later items.
- [x] P2.3 `ClasspathExpander`: `wrapper.java.classpath.N` with `*`/`**` wildcards, directories,
      relative to the working dir, deterministic order, de-duplication. **Done:** results are
      absolute; `*` and `?` work within a segment and in directory segments (`lib/*/*.jar`), as in
      YAJSW; a whole `**` segment (any depth) is a nyajsw extension, since YAJSW's reference says
      recursive wildcards are not supported; wildcards match regular files only, sorted by relative
      path (case-insensitively first, R7); `/` and `\` both separate segments; links are followed
      (loops warn); a directory that cannot be read is skipped with a warning, and only an
      unreadable base directory stops the launch; directories that cannot lead to a match are not
      walked; under Windows rules names match ignoring case, a `\\?\` prefix is literal and a
      drive-relative entry (`C:lib`) is skipped with a warning; `..` after a wildcard is refused
      with a warning; a missing plain entry is kept and a wildcard that matches nothing is dropped,
      both with a warning; the first occurrence of a file wins (by text: short names and links
      are not resolved); messages show entries masked. On macOS, whose file system ignores case,
      names still match case-sensitively.
- [x] P2.4 `ApplicationProcess` + `OutputPump`: check that the Java executable exists before
      starting (YAJSW does), `ProcessBuilder` with `wrapper.app.env.*`,
      `wrapper.working.dir`, `wrapper.tmp.path`, piped output with explicit charset, pid, exit code,
      destroy / process-tree termination helper.
      **Done:** the executable is checked when it has a directory (a bare name is left to the
      `PATH`; `.exe` may be omitted on Windows), and so is the working directory; a value of
      `wrapper.app.env.<variable>` replaces the inherited variable (every spelling of the name on
      Windows, where Java's map is case-sensitive but Windows is not) and an empty one removes it,
      like `set NAME=` in a command prompt; standard input is closed so the application sees its
      end; stdout and stderr are logged at `INFO` as `jvm N`, decoded with the charset the command
      line sets (UTF-8 unless the JVM options choose another, see §4.8), with the configuration's secrets
      masked (an application may echo an option that holds one; a long line is never cut inside a
      secret), in pieces of at most 16 Ki
      characters so output without line breaks cannot fill memory, and drained for at most 2 s
      after the exit, since a grandchild may hold the pipes (that is logged at `STATUS`); the exit
      is logged once however often it is awaited; `terminateTree()` kills the process, then its
      descendants, looking below each again (the job object of P6.4 closes the remaining gap), and
      logs any it could not kill; launch errors are masked and carry no unmasked cause.
      `wrapper.tmp.path` is the wrapper's own directory (`jna.tmpdir`, D12) and is not passed to the
      application.
- [x] P2.5 Console mode `-c` for a single run (no restart loop yet); Ctrl+C waits up to
      `wrapper.shutdown.timeout` for the child, then terminates it (full semantics in P3.6).
      **Done:** `cli.Main` (the only `System.exit`) parses `-c`/`--console <conf>`, `--help`,
      `--version`, and answers the service switches of §3.2 with "not implemented yet" (exit 1);
      configuration errors are logged one per problem through a bootstrap console log (exit 2);
      `ConsoleMode` logs the configuration, classpath and command-line warnings at `STATUS`,
      starts `jvm 1` and returns its exit code; a launch error is one `FATAL` line (exit 1). A
      shutdown hook calls `ConsoleMode.stop()`, which waits `wrapper.shutdown.timeout` and then
      terminates the tree; the hook is removed after a normal end. The console threshold is
      `wrapper.console.loglevel`, raised to `DEBUG` by `wrapper.debug`. The application keys of
      P2.2–P2.4 and `wrapper.console.loglevel`, `wrapper.debug` and `wrapper.shutdown.timeout`,
      which take effect now, are *supported* in the registry, so their invalid values are errors;
      `wrapper.tmp.path` moves to P5, where it becomes `jna.tmpdir` (P5.3). The exit code after
      Ctrl+C is still the JVM's own; P3.6 returns the application's. `run()` returns only after a
      `stop()` in progress has finished, so its last line reaches the log. Limitation until the
      stop strategies (P3.4, P7): the wait suits Ctrl+C in a shared console, where the application
      is asked to stop too; when only the wrapper is stopped (SIGTERM, a closed console window,
      where Windows kills after about 5 s) the application is not asked and is killed after the
      timeout, and a second Ctrl+C does not shorten the wait.
- ~~P2.6 (optional) `wrapper.image` for non-Java executables~~ — dropped: the owner runs only Java
  applications (2026-11-06). `wrapper.image` stays declared, so a file that sets it gets a warning.
Done when: golden command-line tests pass on fixtures; a test main class is launched and its output
captured on Linux and Windows.

### P3 — Supervisor, probe SPI and stop strategies
Goal: restart policy, timeouts and stop sequence, fully unit-tested with fake clock/process.
- [x] P3.1 `Supervisor` state machine and `State` transitions (table-driven tests), including
      "no exit actions while `STOPPING`".
      **Done:** `State.successors()` is the one transition table and the supervisor refuses any
      other move. The supervisor handles one event at a time on the thread that calls `run()` (the
      start, an exit, `requestStop()` from any thread, timers of a `Scheduler`), so tests drive it
      step by step with a fake scheduler and fake processes (`launch.RunningApplication`, which
      `ApplicationProcess` implements, started through `launch.ApplicationLauncher` per
      invocation). A `StateListener` sees every change (for the service host, P6). Interim
      behaviour that later items replace: a started application counts as ready (P3.3); an exit
      applies `LifecycleConfig.exitAction` directly, `RESTART` waits `wrapper.restart.delay` without
      counting failures and `STOP` returns to `IDLE`, where the wrapper waits to be stopped (P3.2);
      a stop waits `wrapper.shutdown.timeout`, then terminates the tree and gives up on a process
      still alive 10 s later, ending `STOPPED` (P3.4); a launch error or an exit that cannot be
      observed ends `FAILED`. Exits while `STOPPING` only end the stop. Any unexpected error while
      handling an event (a collaborator that throws) is logged, a live application is terminated
      and supervision ends `FAILED`, which every state that is not terminal may move to, so the
      wrapper never hangs with the application unwatched. One timer counts at a time; each change
      of state cancels it (`Scheduler.Timer`). The outcome's exit code is that of the last
      invocation, never an earlier one's. The supervisor is not wired into console mode yet (P3.6,
      which also brings the real `Scheduler` and a way for other threads to wait for the outcome).
- [x] P3.2 `ExitActionResolver` (on `config.ExitAction` / `LifecycleConfig.exitAction`) and `RestartPolicy`
      (`restart.delay`, `max_failed_invocations`, `successful_invocation_time`, `startup.delay`),
      `wrapper.control`, and whether `STOP` keeps the wrapper alive under every `wrapper.control`.
      **Done:** `ExitActionResolver` turns the action into restart, end or wait: `STOP` waits in
      `IDLE` only under `LOOSE`/`APPLICATION` (D20). `RestartPolicy` counts failures in a row on
      the scheduler's clock (`Scheduler.now()`). An invocation's run time ends when its process
      ends (`RunningApplication.onProcessEnd()`), not when the exit is reported, which waits for
      the output to drain (up to 2 s when a grandchild holds it) and for the pid file to go. It
      fails when it ends before
      `wrapper.successful_invocation_time` (at exactly that time it is a success) or cannot be
      started; at `wrapper.max_failed_invocations` (values below 1 count as 1; N failures in a
      row, so at most N-1 restarts, D20) the supervisor gives up `FAILED` with an error naming both
      keys and the limit. `STOP` that ends the wrapper says why in the log, and the `on_exit` keys
      note it. A launch error is retried after
      `wrapper.restart.delay` like such a failure (`STARTING → RESTART_WAIT`), as YAJSW's reference
      describes the limit. `wrapper.startup.delay` (now in `LifecycleConfig`) delays only the first
      start, in `IDLE`, where a stop ends supervision. `ControlMode` says which way the lifetimes are
      coupled (`wrapperEndsWithApplication`, `applicationEndsWithWrapper`, also for the job object
      of P6.4); under `LOOSE`/`WRAPPER` a stop request leaves the application running and ends
      `STOPPED`. The keys stay *planned* until console mode runs through the supervisor (P3.6).
- [x] P3.3 `Probe` SPI, `ProbeContext`/`ProbeResult`, `ProbeRegistry`, `ProbeRunner` and
      `ProcessProbe`; `wrapper.startup.timeout` bounds readiness.
      **Done:** `ProbeRegistry.defaults()` maps type names to factories over `ProbeConfig` (only
      `process` so far; P7 adds entries) and refuses unknown types; `ProbeRunner` holds the probes
      that must all pass and the interval, checks them in order up to the first failure (since the
      Phase 7 PR review, probes that `readsEveryCheck`, that is `log`, are still checked after it,
      and the failures are joined), and turns
      an exception (or no result) into a failure named by exception class only, since a message may
      quote a configured value. `ProbeContext` carries the invocation; probes get their settings
      when created, and one instance serves every invocation (per-invocation state is keyed on the
      context's application). The supervisor stays `STARTING` until a check passes: it checks at
      once, then every interval, on a probe executor (a check may block; each check needs its own
      thread so a hung one cannot hold up the next invocation's), and takes the result as an
      event, ignored when the state or invocation changed meanwhile; the task posts a result
      whatever happens. A state may now hold several timers (startup timeout and next check), all
      cancelled when it changes. Not ready within `wrapper.startup.timeout` (0: no limit, as in
      the Tanuki format) → error with the last check's detail, a failed invocation, and the
      application is terminated at once, whatever `wrapper.control` says, as the Tanuki/YAJSW
      startup timeout kills the JVM. The restart waits in `RESTART_WAIT` for the terminated
      invocation to end, then `wrapper.restart.delay`; it never passes through `STOPPING`, so the
      service host (P6) cannot mistake it for a stop. A stop request meanwhile cancels the
      restart, and an invocation still alive 10 s after termination ends `FAILED` instead of being
      restarted beside itself. An exit while `STARTING` applies its exit action as when `RUNNING`,
      and a stop request is handled the same way. `wrapper.readiness.type`/`interval` stay
      *planned* (P7), so the wiring (P3.6) uses their defaults: `process`, every second.
- [x] P3.4 `StopStrategy` SPI, `StopStrategyRegistry`, `TerminateStopStrategy` (process tree),
      `wrapper.stop.strategy` (default `terminate` for now), `wrapper.shutdown.timeout` plus
      `wrapper.jvm_exit.timeout`.
      **Done:** a `StopStrategy` only asks: it gets a `StopStrategy.Context` (invocation, log, its
      share) on the supervisor's thread, returns at once with `REQUESTED` or `NOT_APPLICABLE`, and
      the supervisor waits for the exit. `StopStrategyRegistry.chain(LifecycleConfig)` builds a
      `StopChain` from `wrapper.stop.strategy` and never fails, since a stop must always be
      possible: names ignore case, unknown names (such as `agent` before P7), repetitions and
      strategies that cannot be built are skipped, and `terminate` is moved or added to the end,
      each with a warning for the log (P3.6 logs them). The warnings show configured names through
      the `SecretMasker`, as written (before lower-casing), and a value that holds a secret copied
      in with `${...}` is ignored as a whole with a warning that does not echo it, since once split
      at commas a secret with a comma would reach the log in pieces no masker recognises. The stop budget is
      `LifecycleConfig.stopBudget()` = `wrapper.shutdown.timeout` + `wrapper.jvm_exit.timeout`
      (unset: 0, as YAJSW documents no default), counted to a deadline: each graceful step gets an
      equal share of the time left, so a step that does not apply (or throws, logged) hands its
      share on and the application always has the whole budget. Without graceful steps, or when
      none of them could ask (P7.3c), the whole budget is waited, which suits console mode, where the application gets the same Ctrl+C; with
      a budget of 0 the graceful steps are skipped, since asking and killing in the same instant
      would cut a shutdown short. At the deadline the last step (`terminate`,
      `RunningApplication.terminateTree()`) ends the application, and one still alive 10 s later
      is given up on. Shares are logged with milliseconds when they have them. An unready
      invocation (P3.3) is ended by the last step directly; the safety nets (unexpected error,
      lost exit) kill the tree without the chain, which may be what failed.
- [x] P3.5 `PidFiles` (`wrapper.pidfile`, `wrapper.java.pidfile`).
      **Done:** `wrapperStarted`/`wrapperEnded` write and remove `wrapper.pidfile`;
      `tracking(launcher)` wraps the `ApplicationLauncher` so that `wrapper.java.pidfile` holds the
      pid of each invocation from its start until it has ended, without the supervisor knowing:
      the wrapped invocation reports its exit through `onExit()` only after the file is removed, so
      neither a restart nor the end of the wrapper can race with the removal, and a failing
      `onExit()` still fails inside the supervisor, whose safety net then terminates the
      application. A lost exit leaves the file, since the process may still run. Content: the pid
      and a line separator, US-ASCII, written to `<file>.tmp` beside it (a leftover from a crash is
      reused) and moved over it, so a reader never sees half a number; missing directories are
      created; a stale file is overwritten. A file is removed only while it still holds the pid
      this wrapper wrote, so another instance's file is never deleted. A file that cannot be
      written or removed is an error in the log (path masked, exception kind and the operating
      system's reason), never a failure: the application matters more. On Windows a reader without
      delete sharing makes that happen; it is not retried. Paths are wrapper-side, so relative ones
      resolve against the configuration directory (D13). The keys stay *planned* until P3.6 wires
      the pid files into console mode, which must remove `wrapper.pidfile` after the supervisor's
      outcome.
- [x] P3.6 Console mode runs through the supervisor (restart loop, Ctrl+C semantics, exit code).
      **Done:** `ConsoleMode` builds the stop chain (logging its warnings), the readiness runner
      (`ProbeConfig.readinessTypes()`/`readinessInterval()` hold the registry defaults, `process`
      every second, until P7 makes the keys take effect, through `ConfigValues.constantDefault`),
      the pid files (the wrapper's
      written at the start and removed after the outcome, the application's through the tracking
      launcher), a real `ExecutorScheduler` (one daemon timer thread, `System.nanoTime()`) and a
      virtual thread per readiness check, and runs the supervisor on the calling thread. Each
      invocation expands the classpath again, so jars added since the last start are used; a launch
      error is now retried as the restart policy says (`MainTest` sets `max_failed_invocations=1`).
      Exit code: the last invocation's; without one, 0 when supervision stopped as planned and 1
      (`UNEXPECTED_ERROR`) when it failed; a failed supervision never exits 0, even when the last
      invocation did; an application that survives its termination now ends supervision `FAILED`;
      the last line says whose code it is. Ctrl+C: the shutdown hook (`Main.consoleStopHook`,
      tested with a fake halt) calls `stop()`, which asks the supervisor to stop and waits for
      `run()` to finish, at most the stop budget + 10 s + 10 s, then ends the JVM with
      `Runtime.halt(code)`, so the process exits with the application's code, not the signal's
      (D21). `run()` runs once. `Supervisor.run()` treats an interrupt as a stop request instead of
      leaving the application unwatched. The P3 keys
      (`wrapper.control`, `wrapper.on_exit.*`, `wrapper.restart.delay`,
      `wrapper.max_failed_invocations`, `wrapper.successful_invocation_time`,
      `wrapper.startup.delay`/`timeout`, `wrapper.jvm_exit.timeout`, the pid files and
      `wrapper.stop.strategy`) are *supported*, so their invalid values are now errors (a Tanuki
      `wrapper.on_exit.N=PAUSE` or `HOLD` copied into a YAJSW file now stops the wrapper with exit
      code 2 instead of a warning). A residual race: after Ctrl+C an application that exits before
      the hook's stop request is processed has its exit action applied; the request is queued
      within milliseconds, while the application must first run its own shutdown, so in practice
      the stop wins. Closing the console window gives the wrapper only about 5 s (Windows kills it
      after `CTRL_CLOSE_EVENT`), so the stop budget is not honoured there and pid files may stay.
Done when: all transitions, timeouts and counters are covered by deterministic tests.

### P4 — Logging
Goal: `wrapper.logfile.*` / `wrapper.console.*` behaviour.
- [x] P4.1 YAJSW level names mapping, `wrapper.debug` / `wrapper.debug.level`. **Done:** the level
      names were mapped in P2.1 (`LogLevel`, `WrapperLevel`). `wrapper.debug` raises the console
      level to `DEBUG` (`LogConfig.consoleThreshold()`; the log file's follows in P4.3).
      `wrapper.debug.level` is supported on YAJSW's documented scale (`DebugDetail`): it is read only
      under `wrapper.debug`, where a value that is not a whole number from 0 to 3 is an error (a
      warning while `wrapper.debug` is false, so a leftover cannot stop the wrapper; the key is
      declared as text so that the validator's type check does not make a word fatal there). 0
      writes no debug message (YAJSW's level 0 is the application's pid and stop, which nyajsw logs
      above `DEBUG`), 1 starting and stopping the application (state changes, an exit during a stop,
      the stack trace of an unexpected error),
      2 the steps of supervision as well (stop steps, readiness checks, pid files), 3 everything.
      Every debug message names its detail (`WrapperLog.debug(DebugDetail, …)`); one logged without
      a detail has the most. The bootstrap log moved to P4.3a, since it needs the file sink. So
      `wrapper.debug=true` with `wrapper.debug.level=0` writes fewer debug lines than
      `wrapper.console.loglevel=DEBUG` alone, and the stack trace of an unexpected error needs level
      1; the migration guide (P8.2) should say so.
- [x] P4.2 `LogFormat` tokens for console and file formats. **Done:** the configuration reads both
      format keys into a `config.LineFormat` (fields in the order written, letters in either case);
      a trailing `\n` or `\r\n` (written as those characters, YAJSW's extension) chooses the line
      ending; spaces are ignored and other characters skipped with a configuration warning at their
      line; a format without letters, like a blank one, writes nothing. `log.LineFormatter` renders
      the fields joined by ` | ` (an empty field keeps its separator, so fields stay in place): `L`
      level padded to 6, `P` source padded to 8 (longer ones are not cut), `D` thread name (recorded
      by `WrapperRecord` when the message is logged), `T` local time `yyyy/MM/dd HH:mm:ss`, `Z` the
      same with milliseconds (YAJSW does not document its time
      formats; these are ours), `M` message; a chosen line ending also ends the lines inside the
      message and the stack trace. `WrapperLog.open(LogConfig, console)` builds the configured log:
      `wrapper.console.format` (blank: no console output), its level and the debug detail.
      `wrapper.logfile.format` takes effect with the file sink (P4.3). With the console off and no
      log file, configuration warnings (format ones included) are seen nowhere; the log file of
      P4.3 is where they go then, and in service mode (P6) the bootstrap log of P4.3a.
- [x] P4.3 Rolling log file: rolling by size, `ROLLNUM`/`YYYYMMDD` names, `maxsize`, `maxfiles`,
      Windows rename retries, `wrapper.log.encoding`. **Done:** `RollingFileSink` (the plan's
      `RollingFileHandler`) appends to `wrapper.logfile` (directories created; not set: `wrapper.log`
      beside the configuration file; blank: no file), in `wrapper.logfile.format` from
      `wrapper.logfile.loglevel` up (raised by `wrapper.debug`; `NONE` or a blank format: no file),
      encoded in `wrapper.log.encoding` (default the JVM's charset; an unknown name, or one the JVM
      can only read, is an error) by one encoder per opened file, so that a byte-order mark
      (`UTF-16`) starts a new file only, and
      flushes every record, so the D21 halt loses nothing. A record that would take the file past
      `wrapper.logfile.maxsize` rolls it first (an empty file never rolls, so a huge record is still
      written); copy 1 is the newest, `wrapper.logfile.maxfiles` copies are kept (not set or 0: all;
      negative: an error). `config.LogFileNames` (a name it cannot work with, such as only
      `ROLLNUM` or the token twice, is an error of the key): `YYYYMMDD` is the date the file is
      opened, `ROLLNUM` the copy number, dropped with a `.`/`_`/`-` before it for the current file
      (`wrapper.ROLLNUM.log` → `wrapper.log`, `wrapper.1.log`); without it copies are `<file>.N`;
      tokens only count in the file name. A roll moves the copies below the first free number up
      one (deleting the last copy only when every number up to the limit is taken); each move or
      delete is tried 5 times, 100 ms apart. When one still fails the file is written on, the
      failure reported (masked, without the exception) to the sink's error manager, and the roll
      tried again a minute later by a monotonic ticker; since a retry only fills the gap the failed
      attempt left, a locked file or copy costs no further old copy. A current file removed while
      open is started again without a roll; records that arrive after `close()` are dropped. A file
      that cannot be opened is a masked error on the console, and the log goes on without it.
      `WrapperLog` stamps records from its `Clock`, so the line times and the file's date agree and
      tests are deterministic. A log opened from the configuration also masks the stack traces of
      logged exceptions with the configuration's secrets, on the console and in the file, since
      their messages never pass the configuration layer (this settles the P3.6 review finding that
      the supervisor's stack traces were printed unmasked).
- [x] P4.3b Roll modes: `wrapper.logfile.rollmode` `JVM` (roll at wrapper start and before each
      restarted JVM), `WRAPPER` (at wrapper start), `DATE` (a new file when the date changes, none
      on days without output; `YYYYMMDD` required) with `wrapper.logfile.maxdays`; roll modes and
      `maxsize` combined. **Done:** `JVM` and `WRAPPER` roll a file that is not empty when the sink
      opens it; `JVM` also rolls before every restarted application (`WrapperLog.applicationStarting`,
      called by console mode before each launch; P6 must call it too). `DATE` writes a record to the
      file of the record's day (in the log clock's zone), opened with the day's first record, and
      then deletes the files, rolled copies included, dated more than `wrapper.logfile.maxdays` days
      before that day (`config.LogFileNames.dateOf` recognises them; `-1` keeps all, `0` only the
      day itself); `DATE` without `YYYYMMDD` in `wrapper.logfile` is an error. Rolling by size goes
      on in every mode, `JVM` included, where YAJSW documents no size limit (§3.4): a configuration
      that sets a size wants it honoured, and a restarting application could otherwise fill the
      disk. Tanuki's values, common in files derived from its templates, are accepted (D22):
      `SIZE_OR_JVM` and `SIZE_OR_WRAPPER` as aliases of `JVM` and `WRAPPER` (which roll by size as
      well), `SIZE` (by size only, as without a mode) and `NONE` (never, `maxsize` ignored); any
      other value is an error. Under `DATE` the day only moves forward: a record stamped just before
      midnight that arrives after one stamped just after it, or a clock set back, stays in the
      current file; a clock that jumps ahead deletes the files of the days it skips (registry
      note). The clean-up skips directories and also runs when the day's file cannot be opened.
      `maxdays` without `DATE` is a warning.
- [x] P4.3a Bootstrap log for failures before the configuration is read (and when the configured log
      file cannot be opened): `%TEMP%\nyajsw-bootstrap.log` through the file sink of P4.3, for the
      service mode of P6, where there is no console. **Done:** `WrapperLog.bootstrap(file)` writes
      only to that file in YAJSW's default file format and level (`LPTM`, `INFO`, from the
      registry), UTF-8, and never rolls it: it is written rarely, and the temporary directory is
      writable by every user, where moving and deleting files of a predictable name is best avoided.
      `WrapperLog.bootstrapFile(name)` is `nyajsw-bootstrap-<name>.log` in `java.io.tmpdir`, one
      per service, so services do not share (and roll) one file. When it cannot be opened either,
      the reason goes to standard error and the log writes nowhere, without failing its caller.
      What goes wrong with the configured log file (it cannot be opened, a roll or clean-up fails,
      the disk is full) goes, masked, to the `fileProblems` that `WrapperLog.open(config, console,
      secrets, fileProblems)` takes, which P6 points at the bootstrap log; console mode keeps
      `open(config, console, secrets)`, where those problems are `ERROR` lines on the console when
      it shows them and standard error otherwise. They pass through `ForwardingErrorManager`,
      which replaces the JDK's default (it prints only a sink's first failure, ever): every failure
      is passed on, a repeated one again only after 10 minutes with the count held back, and
      exceptions are shown by class only (their messages could repeat a masked file name); it is
      set before the file is opened, so a failed start roll is heard too. Console mode keeps
      reporting configuration errors on the console.
- [x] P4.4 Application output logged with `jvm N` prefix. **Done:** in place since P2.4
      (`ApplicationProcess` logs both streams through `WrapperLog.forSource("jvm N")`); P4.2 made it
      the `P` field of both formats, padded to line up with `wrapper`. Covered end to end for both
      streams on the console and in the log file, for the first invocation and a restarted one
      (`MainTest`), and by `ApplicationProcessTest`/`OutputPumpTest`.
Done when: rotation and formatting are tested against temp directories; no third-party logging dep.

### P5 — Windows service control (client side)
Goal: `-i -r -t -p -q` against the real SCM through JNA.
- [x] P5.1 `Platform` detection/factory; `platform.unsupported` fallback (`PlatformException` →
      exit code 4 in `cli`). **Done:** `Platform` (name, `serviceController()`; building either
      loads no native code, so `jna.tmpdir` can be set first), `ServiceController`: install from a
      `WrapperConfig`; remove; start and stop by service name, waiting up to a timeout for
      `RUNNING`/`STOPPED`; status, empty when the service is not installed. Starting a running
      service and stopping a stopped one are refusals. `ServiceStatus` holds the `ServiceState` (the
      SCM's states; an unknown one is a failure) and the pid while there is one; it may grow for
      `-q` (start type, account, the exit code of a failed service). `Platforms` picks the first
      registered entry that claims the host's `os.name`, else `UnsupportedPlatform`, whose service
      operations throw `UnsupportedPlatformException` naming the host and saying that `-c` works;
      Windows registers with its controller in P5.2. Exit code 4 is for that subclass only
      (`ExitCode.of`, D23). The CLI routes the service commands through the platform in P5.4.
- [x] P5.2 `ScmApi` facade + `JnaScmApi` (adds `jna`/`jna-platform` to `nyajsw-wrapper`; last-error handling in one place); hand-written
      `SERVICE_DELAYED_AUTO_START_INFO` / `SERVICE_PRESHUTDOWN_INFO` with layout tests;
      `WindowsServiceController` (install with start type, dependencies as `\0`-separated list with
      a test, account, description, delayed start, pre-shutdown timeout with margin; remove; start; stop; status
      with pid), minimal access rights per command, wait-for-state helper, `Win32Errors` mapping.
      **Done:** `ScmApi` has one method per call (per info level of `ChangeServiceConfig2`) in
      plain types: `ScmHandle` (the native value), `ScmStatus`, `ServiceRegistration` (whose
      `dependencyList()` ends each name with a NUL; `toString()` hides the binary path and
      password). `JnaScmApi` is the only class that reads the last error; it throws
      `platform.ServiceException` (with `errorCode()`) worded by `Win32Errors`: the system's text,
      the code, and a hint for 5, 1053, 1057, 1069, 1072 and 1073. The structures live in
      `platform.windows.jna` with layout tests. `WindowsServiceController` opens the manager and the
      service with the least access (`-q`: `SC_MANAGER_CONNECT` and `SERVICE_QUERY_STATUS` only),
      closes both handles always, and says what it attempted in every failure. Install registers
      the `ServiceCommandLine` binary path (display name defaulting to the name, a missing name a
      configuration error), sets the description, the delayed start and always the pre-shutdown
      timeout (`Supervisor.longestStop` of the stop budget plus 10 s, the computation console mode
      now shares), deletes a service it could not configure, and returns the command line's
      warnings. Start and stop poll through `StateWait` (a tenth of the wait hint, 100 ms to 1 s,
      on a monotonic ticker) until `RUNNING`/`STOPPED`; a service that stops while starting fails
      with its exit codes. Status maps 1060 to "not installed" and an unknown state to a failure.
      `WindowsPlatform` is registered in `Platforms`; `ServiceException` maps to exit code 3 (D23).
      JNA loads at the first call (D5).
- [x] P5.3 `ServiceCommandLine`: binary path from `java.home`, an explicit classpath parameter
      (default `java.class.path`; integration tests pass the real jars because surefire's
      `java.class.path` is a manifest-only booter jar), `wrapper.ntservice.additional.N`,
      `-Djna.tmpdir` from `wrapper.tmp.path`, quoting (D12); `wrapper.ntservice.java.command` (a
      Java 21 only). **Done:** `platform.windows.ServiceCommandLine.build` gives a `ServiceCommand`:
      the arguments (`javaw.exe` of the given `java.home`, the JVM options, `-Djna.tmpdir`, `-cp`,
      `cli.Main`, `--service`, the absolute configuration file), warnings, the binary path joined
      by `WindowsArguments` (the C runtime's quoting, which the Java launcher's parser follows) and
      a loggable form masked argument by argument. Classpath entries and paths are absolute and
      normal, since the service does not start in the installing directory. The JVM options are
      unquoted like `wrapper.java.additional.N` (`JavaCommandBuilder.unquote`, now public), an
      empty one is skipped with a warning, and `-Djna.tmpdir` comes after them so it is the one in
      effect. The Java launcher reads its command line in the ANSI code page (`native.encoding`), so
      an argument that code page cannot hold is a configuration error, not a service that finds
      `?` in its paths; a `%NAME%` gives a warning, since Windows expands the stored binary path.
      `wrapper.ntservice.java.command` (`ServiceConfig.javaCommand`, read like
      `wrapper.java.command` through `JavaConfig.executable`, with a directory resolved against the
      configuration directory) must have a directory and exist; a Java older than 21 by its
      installation's `release` file (followed through links, and above a JDK 8's `jre`) is a
      configuration error, an unknown version a warning (D12). The keys stay `PLANNED` until `-i`
      writes the binary path (P5.4).
- [x] P5.4 CLI commands `-i -r -t -p -q` with human-readable output and exit codes. **Done:**
      `Main` dispatches the five switches and their long forms to `cli.ServiceCommands`, which
      loads the configuration like console mode (one shared `load`), prints its warnings, needs
      the service name (`ServiceConfig.requiredName()`, shared with install) and runs the command
      on `Platforms.forThisHost()`'s controller. Output goes to the console only, never to the log
      file, so a command beside a running service cannot roll its log (P4.3b). `-i` prints the
      command line's warnings (the logon right of the account follows in P5.5).
      `-t` waits for `RUNNING` through `wrapper.startup.delay`, `wrapper.startup.timeout` and 30 s
      (no limit for a timeout of 0); restarts before the application was ever ready can take
      longer. `-p` waits for `STOPPED` as long as console mode waits for a stop and the
      pre-shutdown timeout allows (one `Supervisor.stopWait`; every wait is cut to
      `Supervisor.LONGEST_WAIT`, so huge configured times cannot overflow). `-r` says Windows
      deletes the service once it has stopped; it does not stop it first. `-q` prints the state and
      pid, or "not installed" (exit 0: an answer, not a failure). Failures are printed masked and
      end by `ExitCode.of`: 2 configuration, 3 the SCM refused, 4 no services on this host, 1
      otherwise, a native library that cannot be loaded included (a readable `PlatformException`
      naming `jna.tmpdir`). `jna.tmpdir` is set from `wrapper.tmp.path` when the configuration is
      loaded, for every command, unless the command line sets it. The `wrapper.ntservice.*` keys of
      install, `wrapper.ntservice.additional.N`, `wrapper.ntservice.java.command` and
      `wrapper.tmp.path` are now `SUPPORTED`, so a bad value in them is an error in console mode
      too. Until P6 implements `--service`, an installed service does not run: `-t` fails.
- ~~P5.4a Migration entry point: `WrapperServiceBooter` alias class, mode detection when `--service`
  is absent, and the Java 21 binPath rewrite in a migration command~~ — dropped for now: the owner
  migrates by reinstalling: stop (`-p`) and remove (`-r`) the service with YAJSW, then install it
  with nyajsw's `-i` on the same `wrapper.conf` (§10 Q8, 2026-11-07). D17 keeps the design in case
  taking over existing registrations is wanted later.
- [x] P5.5 Grant `SeServiceLogonRight` at install when `wrapper.ntservice.account` names a real
      account (skipped for the built-in `LocalSystem`/`LocalService`/`NetworkService`); hand-written
      LSA binding; clear error when it cannot be granted. **Done:** before `CreateService`, install
      checks an account that needs the right (`ServiceConfig.needsServiceLogonRight()`: not no
      account, `LocalSystem`, `SYSTEM`, `LocalService`, `NetworkService` (also with a space), alone
      or after `.\` or `NT AUTHORITY\`, in any case, nor a virtual account `NT SERVICE\name`, which
      has the right already and cannot be looked up before its service exists):
      `LsaApi.userSid` (`jna-platform`'s `Advapi32Util.getAccountByName`, `.\name` looked up as
      `<computer>\name` so that a domain account of the same name is never found) must find a user,
      since a group such as `Users` would let all its members log on as a service. Only once the
      service is created and configured is `SeServiceLogonRight` granted (`LsaApi.grantServiceLogon`:
      `LsaOpenPolicy` with `POLICY_CREATE_ACCOUNT | POLICY_LOOKUP_NAMES`, `LsaAddAccountRights`,
      `LsaClose`, an `NTSTATUS` below 0 turned into a Win32 code by `LsaNtStatusToWinError`), inside
      the block that deletes a half-installed service, so a failed install grants nothing and leaves
      nothing. The binding is hand-written: `platform.windows.jna.Advapi32Lsa` and
      `LsaObjectAttributes` (layout test). Failures say which account and why (1332 "no such
      account" has a hint, 5 the Administrator one); `JnaLsaApi` words its errors through
      `JnaScmApi.failure`, one place for the system text. The right stays when the service is
      removed, as the Services console leaves it.
- [x] P5.6 `windows-integration` tests: install → query → remove a throwaway service (start/stop
      come with P6), cleanup in `@AfterMethod(alwaysRun = true)`. **Done:**
      `platform.windows.WindowsServiceIntegrationTest` (group `windows-integration`) installs a
      `nyajsw-test-<random>` demand-start service, queries it as `STOPPED` without a pid, reads the
      registration back from `HKLM\SYSTEM\CurrentControlSet\Services\<name>` (binary path, display
      name, description, `LocalSystem`, own process, start type, both dependencies, pre-shutdown
      timeout), removes it and sees it gone; a delayed one is automatic with `DelayedAutostart=1`; a
      second install is refused with 1073 and keeps the first; a service that is not installed has
      no status. All four pass on Windows 10 in a local elevated shell (`mvn -q verify
      -Pwindows-integration` as Administrator; the CI job waits for P0.3) and leave no
      `nyajsw-test-` service behind. That run proves `CreateService`, `ChangeServiceConfig2` and
      `DeleteService` against the real SCM. An earlier unelevated run was refused at install with 5
      and the Administrator hint (no test asserts it). The LSA grant needs a real local user and is
      not covered.
Done when: unit tests cover every `ScmApi` interaction with mocks; integration job green on Windows.

### P6 — Service host (running as the service)
Goal: the wrapper itself runs under the SCM.
- [x] P6.1 `ServiceHost` interface + `FakeServiceHost` for tests; `WindowsServiceHost` with
      `StartServiceCtrlDispatcher`, `RegisterServiceCtrlHandlerEx`, `SetServiceStatus`,
      checkpoint/wait-hint reporting, `STOP`, `SHUTDOWN`, `PRESHUTDOWN` (accepted by default),
      `INTERROGATE`; named callback thread; `STOPPED` reported last. **Done:** `ServiceHost.run`
      runs a `ServiceBody` (`run(ServiceContext)` → `ServiceExit`, `stop(ServiceControl)`) and
      returns once `STOPPED` is reported, rethrowing what the body threw; the body reports
      `starting(waitHint)`, `running()` and `stopping(waitHint)`. `Platform.serviceHost()` gives
      `WindowsServiceHost` on `JnaServiceHostApi` (no hand-written binding: `jna-platform` has the
      three calls and their callback types), and on other hosts `UnsupportedServiceHost` (exit code
      4). The checkpoint restarts at 1 in each pending state; a failed `ServiceExit` is reported as
      1066 with its code, an exception of the body as 1066 with code 1. The callbacks are held in
      static fields, the one sanctioned static state. Shared with the controller:
      `NativeLibrary.cannotLoad` for a JNA that cannot be loaded. Tested on a fake dispatcher
      (`FakeServiceHostApi`, which runs the service main on a thread of its own and sends controls
      on the dispatcher's); `FakeServiceHost` is the platform-neutral double for P6.2. On Windows
      the real `StartServiceCtrlDispatcher` fails with 1063 from a console process
      (`windows-integration`). If `RegisterServiceCtrlHandlerEx` or the final `SetServiceStatus`
      fails, Windows never releases the dispatcher, which runs on a daemon thread
      (`nyajsw-service-dispatcher`) for that reason: `run` returns by itself and throws the
      failure, so the caller can log it and end the process. Reports go one way (no
      `START_PENDING` after `RUNNING`, nothing after `STOPPED`), and a pending wait hint is at least
      2 s. `FakeServiceHost` accepts a stop only while `RUNNING`, as Windows does.
- [x] P6.2 `--service <conf>` command: dispatcher first, then configuration, file-only logging,
      supervisor wiring with `START_PENDING` until `ProbeRunner` readiness (`process` probe),
      `ERROR_SERVICE_SPECIFIC_ERROR` on `FAILED`; `-Xrs` for the application JVM in service mode
      while `wrapper.ntservice.reduce_signals` is true (it also keeps a future `console` stop
      strategy from working, §4.6). **Done:** `Main --service <conf>` hands a `cli.ServiceMode` to
      `Platform.serviceHost()` before reading anything. On the service thread it reads the
      configuration (problems go to the bootstrap log `nyajsw-bootstrap-<name>.log`, and the
      service fails with code 2), opens the configured log with no console (the log file's own
      problems go to the bootstrap log), and runs the supervision stack, now shared with console
      mode as `cli.Supervision` (the deferred P3.6 finding). `ServiceStatusReporter` maps the
      supervisor's states: `START_PENDING` until the first ready, `RUNNING` from then on (a restart
      of a running application does not show), `STOP_PENDING` while stopping; a pending state is
      reported again every half wait hint (twice the readiness interval, at least 2 s), on a timer
      of its own, so the checkpoint moves for as long as the supervisor's bounded start or stop
      lasts. A stop control logs the reason and asks the supervisor to stop; one before
      supervision begins starts nothing. Supervision that ends `FAILED` reports 1066 with console
      mode's exit code; for `STOPPED` see below. `JavaCommandBuilder.build` takes the wrapper's own JVM options, placed after
      `-Djava.library.path` and before `wrapper.java.additional.<n>`: `-Xrs` while
      `wrapper.ntservice.reduce_signals` (now `SUPPORTED`) is true. What keeps the process from
      running as a service at all goes to the console and, once Windows has named the service, to
      its bootstrap log; the exit code follows D23. A configured name that differs from the one
      Windows started is a warning. Tested under `FakeServiceHost` with a real application JVM; the
      run under the real SCM is P6.5. After review: supervision that waits idle (an exit with
      action `STOP` under `LOOSE`/`APPLICATION`) is a running service, so it can be stopped; a
      pending state is reported for no longer than `-t` or `-p` would wait (`startTimeout`,
      `stopWait`), after which Windows shows it as not responding instead of starting forever; a
      failed report is tried again; the configuration is read under a 30 s wait hint; the
      bootstrap log is opened only when there is something to write; an application that ends
      by itself with a code other than 0 fails the service with that code (as console mode exits
      with it), while a stop the SCM asked for is success whatever the code; a stop chain without
      a graceful step is warned about at start (deferred finding). A start that never becomes ready
      with `wrapper.startup.timeout=0`, or a long `wrapper.startup.delay`, stays `START_PENDING`,
      where Windows refuses a stop (1061).
- [x] P6.3 Service-account hardening: working directory default, clear errors for unwritable
      log/pid paths, documentation of `jna.tmpdir` / `jnidispatch.dll` options. **Done:** the
      working directory needed no change: it defaults to the configuration's directory (D13),
      wrapper-side relative paths resolve against that directory and application-side ones against
      the working directory, so the service's own current directory (`C:\Windows\System32`) never
      matters, and the installed command line names the configuration by its absolute path (D12). A log file or pid file that cannot be written
      because access is denied says which account the wrapper runs as (`user.name`) and which key
      chose the place (`log.WriteAccess`); the log file's message reaches the bootstrap log in
      service mode. The bootstrap log moves beside the configuration file
      (`WrapperLog.bootstrapFile(directory, name)`; deferred finding P4.3a), which is protected as
      well as the configuration itself, where `C:\Windows\Temp` lets any user plant a file first;
      when the account may not create it there, it falls back to the account's temporary directory
      (`cli.BootstrapLog`), and before Windows has named the service (JNA cannot be loaded, the
      process cannot connect) it is named after the configuration file. User guide
      `docs/windows-service.md`: the commands, what needs a reinstall (the registration's keys,
      the stop timeouts, the file locations, a rename), starting and stopping (the default stop
      chain, 1066 and `SERVICE_EXIT_CODE`, `-Xrs`), accounts and what they must read and write,
      protecting the installation, the two logs, the application JVM given with its directory, and
      `jna.tmpdir` with the `jna.boot.library.path` / `jna.nounpack` fallback for
      `jnidispatch.dll`.
- [x] P6.4 Job object (hand-written binding) with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` for
      `TIGHT`/`APPLICATION` (the modes in which the application ends with the wrapper, §4.6);
      `TerminateJobObject` used by the `terminate` strategy when present. **Done:**
      `Platform.processTools()` gives `ProcessTools.createKillOnCloseJob()` → `ProcessJob` (`add`,
      `terminate`, `close`); `WindowsProcessTools` on the `ProcessApi` facade (`JnaProcessApi`: the
      hand-written `Kernel32Jobs` binding with `JOBOBJECT_BASIC_LIMIT_INFORMATION` and
      `JOBOBJECT_EXTENDED_LIMIT_INFORMATION`, layout-tested, and `jna-platform`'s `OpenProcess` and
      `CloseHandle`), `ProcessTools.NONE` elsewhere. `Supervision` makes one job per supervision
      under `TIGHT`/`APPLICATION`, in console mode as in service mode, and closes it when
      supervision ends; under `LOOSE`/`WRAPPER` there is none, since the application may outlive
      the wrapper. `ApplicationProcess` adds each invocation right after it starts, and
      `terminateTree` ends the job first (`TerminateJobObject`, exit code 1 as
      `destroyForcibly`), then still walks the process tree for anything outside it. A job that
      cannot be created, or a process it refuses, is an error in the log, and the application runs
      without it. What a process started before it was added stays outside, as does an earlier
      invocation's orphan only until the job ends: one job serves all invocations, so `terminate`
      also ends orphans an earlier invocation left. Tested with a mocked facade, a recording job and
      counting tools; on Windows `WindowsProcessToolsIntegrationTest` (`windows-integration`, no
      elevation needed) shows that closing or ending a real job ends a `TestApplication` and the
      grandchild it starts, and the console-mode tests run under a real job. `Win32Failures` now
      words every facade's failures (the deferred P5.5 finding), without the service commands'
      hints for process calls. After review: the job is created when supervision runs, not when it
      is built; a process that ended before it could join the job (Windows refuses it with 5) is
      a debug line, not an error; the tree is read before the job is ended; the user guide says
      that under `TIGHT`/`APPLICATION` everything the application started ends with supervision.
- [x] P6.5 `windows-integration` end-to-end: install → start → `RUNNING` → stop → `STOPPED` →
      remove, with a test application and the wrapper log attached on failure. **Done:**
      (`platform.windows.WindowsServiceEndToEndTest`, group `windows-integration`): the service's
      binary path runs `--service` on the test JVM's full classpath (surefire's
      `surefire.test.class.path`: the wrapper's classes, JNA, the test application); a
      `TestApplication` service is started until `RUNNING` with a pid, its log shows the service
      name and `-Xrs` in the application's JVM options, it is stopped until `STOPPED` with Win32
      exit code 0 and both processes gone; an application that ends with 3 stops the service with
      1066 and `dwServiceSpecificExitCode` (`sc query`'s `SERVICE_EXIT_CODE`) 3; a configuration
      broken after the install stops it with 1066 and 2, its error in the bootstrap log beside the
      configuration. A failed test prints the wrapper log and both bootstrap logs into the report
      (the service's `java.io.tmpdir` is in the test's directory, so the fallback one is too).
      After each test the service is stopped (a pending one is waited for, then killed as a last
      resort) and removed, and the directory is deleted once the wrapper's process has let go of
      the JNA library it unpacked there; that fixture, `ThrowawayService`, is shared with the P5.6
      test, which now also installs the real classpath. Unelevated on Windows 10, the registered
      command line, run from a console, starts the wrapper, which refuses with 1063 and the hint,
      exit code 3, in the bootstrap log named after the configuration file; install is refused
      with 5. The owner's elevated run (`mvn -q verify -Pwindows-integration` as Administrator,
      Windows 10, 2026-11-08) passed: the four end-to-end tests, the five P5.6 tests and the two
      job-object tests, none skipped. The configuration is a minimal generated one, so the phase's
      "unmodified YAJSW-style `wrapper.conf`" is left to a real fixture in P8.3.
Done when: a real service runs a test application from an unmodified YAJSW-style `wrapper.conf`.

### P7 — Probes and agent
Goal: configurable readiness/liveness; graceful stop through the agent.
- [x] P7.1 `wrapper.readiness.*` / `wrapper.liveness.*` configuration (probe selection, intervals,
      failure counts) and liveness failures → action. The `START_PENDING`-until-ready flow already
      exists from P6 with the `process` probe.
      **Done:** `wrapper.readiness.type`/`interval` and `wrapper.liveness.type`/`interval`/
      `failures`/`action` are supported (the per-probe keys stay planned for P7.2). An interval of
      0 or a failure count below 1 is a configuration error; a type list holding a secret is
      ignored whole with a warning, the default applying, through the helper `wrapper.stop.strategy`
      now shares (`ConfigValues.names`). Type names ignore case; an unknown type is a
      `ConfigException` naming the key when supervision is set up, so console and service mode end
      with exit code 2 before anything starts. `Supervisor` takes a `Liveness` (probes, failures,
      action; `Liveness.NONE` without probes) and checks it while `RUNNING`: first one interval
      after readiness, the next only once the previous result is in; once the process has ended
      checks no longer count, so the exit action decides an exit seen during the output drain.
      Enough failures in a row terminate the application at once and then restart it (its run time
      counts towards `wrapper.successful_invocation_time` up to when the last passing check began,
      readiness or liveness, so neither a slow failing check nor the interval before it counts as
      healthy) or, after waiting in `STOPPING` for it to end, end supervision `FAILED` (D24). Tests: fake clock in
      `SupervisorLivenessTest`; the configuration, console and service-mode errors end to end.
- [x] P7.2 `TcpProbe`, `HttpProbe`, `LogPatternProbe`, `FileProbe`, `CommandProbe` (tests with
      local `ServerSocket`/`HttpServer`, temp files, fake clock). In three commits:
  - [x] P7.2a `tcp`, `http`, `file`. **Done:** registered in `ProbeRegistry`; each serves readiness
        and liveness from the same `wrapper.readiness.<type>.*` keys, now supported. `tcp` opens and
        closes a connection (5 s connect timeout, the host looked up on every check); `http` sends
        a `GET` with `java.net.http.HttpClient` over HTTP/1.1 and without a proxy (redirects not
        followed, so a `3xx` is judged by its own status; `wrapper.readiness.http.timeout`, default
        5 s, bounds the whole exchange, body included, so an application that sends its headers and
        then hangs fails the check instead of blocking liveness; user information in the URL is not
        sent and is warned about); `file` passes while the path exists, relative to
        `wrapper.working.dir`. Readiness and liveness share the per-probe keys (D25).
        `wrapper.readiness.http.status` is read into `config.StatusRanges` (codes and ranges
        separated by commas). Invalid values are configuration errors with their location: a port
        outside 1–65535, a URL that is not absolute `http`/`https` with a host (not quoted, since
        it may hold credentials), unreadable status ranges, a timeout of 0. A probe listed without
        its required key is a configuration error naming that key when supervision is set up. Log
        messages show an http target without user information, query or fragment.
  - [x] P7.2b `command`. **Done:** `wrapper.readiness.command` is split into an argument array by
        `ValueParser.parseCommand` (blanks separate, double quotes group and are removed, no escape
        character; an open quote or a missing program is a configuration error that does not quote
        the value) and run without a shell in `wrapper.working.dir`, with the wrapper's
        environment, its output discarded and its input closed at once. The program follows the
        rule of the other executable keys: a bare name is looked up on `PATH`, one with a directory
        is resolved against the working directory (Windows would look from the wrapper's own,
        `System32` for a service). Exit code 0 passes; one that overruns
        `wrapper.readiness.command.timeout` (now supported, default 10 s, at least 1 s) is killed
        with its descendants through `launch.ProcessTrees`, the tree walk `terminateTree` now
        shares. Log messages name the program and the JDK's reason it cannot be started, never the
        arguments. Duration texts in log messages share `log.Durations`.
  - [x] P7.2c `log` (captured output or `wrapper.readiness.log.file`). **Done:** passes when a
        line that came since the previous check (for the first check of an invocation, since its
        launch) contains a match of `wrapper.readiness.log.pattern` (compiled in `ProbeConfig`; an
        invalid one is a configuration error giving the reason, not the whole pattern). As liveness
        that is a heartbeat message, which must come once per interval; readiness and liveness share
        the pattern (D25), so both need an alternation such as `Started|alive`. As readiness, a
        match keeps passing for the rest of the invocation's start (PR review): with `log,http` the
        start-up line often comes while the port still refuses, and the whole check fails then.
        The captured output reaches it through `RunningApplication.output()`: the output pumps
        also hand every line, as logged (masked, long ones in pieces), to the invocation's
        `launch.OutputLines`, which keeps the latest 10,000 lines and 2 million characters and
        tells a reader how many it missed; `PidFiles` passes it on. With
        `wrapper.readiness.log.file` (relative to the working directory) the probe reads, as UTF-8,
        the lines appended since the launch: `Probe.beforeLaunch`, a new default hook the supervisor
        calls on the readiness and liveness probes just before each launch, notes the file's size,
        so that a liveness probe first checked an interval after readiness, or a log probe listed
        after one that failed, still sees the ready line. At most 16 MiB are read per check; a file
        that shrank is read from its start, one that does not exist yet whole once it appears, and a
        line without its end yet is matched as far as it goes and counts once. Each invocation
        starts afresh; a check that finds the previous one still running (a hanging file share)
        fails at once. `RunningApplication.output()` is abstract, so that a wrapper of it cannot
        forget to pass it on.
- [ ] P7.3 `nyajsw-agent`: `premain`, `AgentProtocol`, loopback connection with token from
      `NYAJSW_AGENT_TOKEN`, `STOP` → `System.exit(0)`; wrapper side `agentlink` (`AgentListener`,
      `AgentChannel`), `AgentProbe`, `AgentStopStrategy`; automatic `-javaagent` injection unless
      `wrapper.agent.enabled=false`; default stop strategy becomes `agent,terminate` and default
      liveness `agent` when enabled; `wrapper.ping.*` mapping (both `wrapper.ping.*` and
      `wrapper.liveness.*` have constant defaults, so the mapping must tell a value set in the file
      from a default by its `ConfigLayer`); round-trip test with both ends. In three commits:
  - [x] P7.3a the agent and the protocol. **Done:** `nyajsw-agent` (Java 8, no dependencies) holds
        `AgentProtocol`, the one definition both ends use: the port as `-Dnyajsw.agent.port`, the
        token as `NYAJSW_AGENT_TOKEN`, lines of ASCII of at most 256 bytes (one cut off by the end
        of the stream is no message), `HELLO 1 <token>` from the agent (`helloVersion` reads the
        version apart from the token, so a newer wrapper can accept and name an older agent), then
        `PING <n>`/`PONG <n>` (the answer repeats what its ping carried, so a late answer is not
        taken for the next one's) and `STOP`/`STOPPING` (followed by `System.exit(0)`, so the
        application's shutdown hooks run, also when the acknowledgement cannot be written),
        `UNKNOWN` for anything else; random 64-digit tokens, compared in constant time; both ends
        on `127.0.0.1` (`AgentProtocol.loopback()`), not the JVM's loopback, which is `::1` when it
        prefers IPv6. `Agent.premain` starts one daemon thread and returns: it connects (20
        attempts, 250 ms apart), and whatever goes wrong (no port or token, no wrapper to reach, a
        security manager that forbids reading the property, connecting or exiting) costs one line
        on standard error and the agent, never the application; a wrapper that goes away ends the
        thread, not the application. The jar's manifest names `Premain-Class`. Tests: the protocol,
        the agent against a fake wrapper end in the test JVM, and the agent as `-javaagent` in a
        real JVM (exit code 0 and the shutdown hook after `STOP`; under a security manager the
        application still starts).
        Constraints for P7.3b: authentication is one-way (the agent sends its token to whoever
        accepted and obeys its `STOP`), so the wrapper must bind its listener (exclusively, on
        `127.0.0.1`) before the launch, accept one authenticated session per invocation and then
        stop listening; processes the application starts inherit `NYAJSW_AGENT_TOKEN`, so a second
        session with the right token must be refused too.
  - [x] P7.3b the wrapper side: `agentlink`, `-javaagent` injection, `AgentProbe`,
        `AgentStopStrategy`. **Done:** `agentlink.AgentListener` is opened per invocation before
        the launch: it binds a port of its own on `127.0.0.1` without address reuse, draws a token,
        takes the first connection that introduces itself with it (protocol 1) as the
        `AgentChannel` and then stops listening, so no second session can follow. Every connection
        is introduced on a thread of its own and must send its whole `HELLO` line within 2 s, so a
        local user who connects and says nothing, or drips bytes, cannot keep the agent out; at
        most 16 introductions run at once. Another version or token, or a late `HELLO`, is refused:
        the first refusal as a warning, later ones at debug level, never showing the token. Without
        an agent after 60 s the listener stops with a warning. The channel pings with a number and
        a timeout (a late answer is skipped, a slow one keeps the session, a broken one closes it)
        and asks for the stop, one exchange at a time behind a lock whose wait counts against each
        exchange's own timeout (no monitor, so a waiting virtual thread does not pin its carrier).
        `ApplicationProcess.start` takes the listener: its token goes into the environment as
        `NYAJSW_AGENT_TOKEN` (removed when there is no agent, so the application never sees one of
        somebody else's), `-javaagent:<jar>` and `-Dnyajsw.agent.port` among the wrapper's JVM
        options, and the listener is closed when the process has ended; `RunningApplication.agent()`
        (abstract, passed on by `PidFiles`) is the open session. `agentlink.AgentJar` finds the
        jar: the one the agent classes came from when its manifest names the agent, else
        `nyajsw-agent.jar` beside it. `Supervision` injects the agent when it is in effect and the
        application is a JVM (not with `wrapper.image`), warning when the jar is not found (or its
        path holds `=`, which `-javaagent` would cut), and when a probe list or the stop chain names
        the agent while it is not injected; until P7.3c makes `wrapper.agent.enabled` supported it
        never is. New: the `agent` probe (the
        session answers a ping within 30 s, YAJSW's `wrapper.ping.timeout`) and the `agent` stop
        strategy (asks on a thread of its own and returns; without a session it does not apply).
        Tests: the listener and channel against a fake agent on real sockets, the probe and the
        strategy, the jar lookup, and both real ends: the packaged agent in a child JVM connects,
        answers and ends it with exit code 0 (skipped when the agent is not packaged, as in
        `-pl nyajsw-wrapper`).
  - [x] P7.3c the defaults (`agent,terminate`, liveness `agent`) and the `wrapper.ping.*` mapping.
        **Done:** `wrapper.agent.enabled` (default `true`, D10), `wrapper.ping.interval` and
        `wrapper.ping.timeout` are supported, so every JVM application gets the agent unless the
        file turns it off. One predicate, `ProbeConfig.agentInEffect()` (enabled, implemented, and
        the application a JVM: no `wrapper.image` once that key takes effect), decides the
        injection and both derived defaults: `wrapper.stop.strategy` becomes `agent,terminate` and
        `wrapper.liveness.type` becomes `agent`. The ping keys moved from `LifecycleConfig` to
        `ProbeConfig` (P1.6b): `wrapper.ping.timeout` (30 s) is how long the `agent` probe waits for
        each answer, and `wrapper.ping.interval` sets the liveness interval when the file writes it
        and not `wrapper.liveness.interval` (both written: the nyajsw key wins, with a warning); an
        interval or timeout of 0 is an error. Each invocation reports `agentlink.AgentState` (none,
        waiting, connected, lost) and each check its purpose (`ProbeContext.Purpose`): the `agent`
        probe passes without checking an invocation that runs without the agent (no jar, the
        listener could not open, the agent never connected within the listener's minute) so a
        healthy application is never restarted for it, fails readiness but passes liveness while
        the agent has not connected yet, and fails a lost session or an unanswered ping. Tests: the
        defaults, the mapping, the states, and a console stop that ends a real application with 0
        through the agent, well within its budget; the tests of the terminate path turn the agent
        off. After review: when no graceful step could ask (no agent connected), the stop budget is
        still waited before `terminate`, so Ctrl+C in console mode still gives the application
        its shutdown time; a runtime whose `release` file lists its modules without
        `java.instrument` (a `jlink` image, which would not start with `-javaagent`) gets no agent,
        with a warning naming `wrapper.agent.enabled=false`; a ping key of 0 is Tanuki's "no
        heartbeat", not an error: the agent liveness default is off then, and an explicit `agent`
        probe only fails on a lost session. A written `wrapper.ping.timeout` keeps YAJSW's
        meaning, the silence that counts as a hang: with the agent as the only liveness probe and
        `wrapper.liveness.failures` not written, pings of up to one interval each, as many failed
        in a row as fit into the timeout. Warnings about lists that name the agent appear only
        where the file named it. Constraints from the P7.3b review: the derived defaults must follow the same predicate as
        the injection (`Supervision.agentWanted`: in effect and no `wrapper.image`), and an
        invocation that runs without the agent (no jar found, the listener could not be opened)
        must not fail a default `agent` liveness probe; expose the per-invocation state (no agent,
        waiting, connected, lost) rather than an empty `Optional`. Test `Supervision.launch`'s
        injection (the options, the listener closed when the start fails) once it is switched on.
- [x] P7.4 `docs/probes.md` user guide. **Done:** readiness and liveness (keys, timing, what a
      failure does), the settings the two share (D25) and the errors they can give, each probe
      type with its settings and pitfalls (a leftover file, redirects, user information, the
      command's environment and `.cmd` files, the output buffer, one pattern for both), the agent
      (what it does, when it is not injected, how a stop uses it, the local channel), YAJSW's
      `wrapper.ping.*` mapping, examples, and the log messages to look for. After review: Windows
      recovery acts on a stop with error 1066 only when the service is set up for it, which nyajsw
      does not do yet (risk R6); a readiness file must be gone before the launch, since the first
      check runs at once; a service whose first start keeps failing cannot be stopped meanwhile;
      `wrapper.conf` escaping applies to patterns and commands; the cases without the agent and the
      ping defaults of a file that does not write them are spelled out. `windows-service.md` links
      to the guide.
Done when: each probe has unit tests; an end-to-end Windows test reaches `RUNNING` only after an
HTTP probe passes and stops gracefully through the agent. The test is
`WindowsServiceEndToEndTest.theServiceRunsOnlyOnceItsHttpProbePassesAndStopsGracefullyThroughTheAgent`
(group `windows-integration`); it passed in an elevated run on 2026-11-08, with the rest of the
group. Phase 7 is done.

### P8 — Packaging, migration, release
- [ ] P8.1 `nyajsw-dist`: shaded `nyajsw.jar` (no relocation of `com.sun.jna`, native resources
      kept), `nyajsw-agent.jar`, `bin/*.bat` mirroring YAJSW script names, `conf/wrapper.conf`
      fully commented template, zip assembly.
- [ ] P8.2 `docs/migration-from-yajsw.md`: the reinstall procedure (§10 Q8): as Administrator,
      stop (`-p`) and remove (`-r`) the service with YAJSW before deleting the YAJSW installation,
      wait until it is gone (close the Services console; until then `-i` fails with 1072,
      `ERROR_SERVICE_MARKED_FOR_DELETE`), then `-i` with nyajsw on the same `wrapper.conf`. The new
      registration names a Java 21+ JVM: the installing JVM's `javaw.exe`, or
      `wrapper.ntservice.java.command`, which `-i` rejects when older than 21 (D12). Settings made
      on the old registration outside `wrapper.conf` are not carried over (binary-path edits with
      `sc config` such as `-D` flags, a password entered in the Services console, recovery actions,
      a start type changed in the console); move them into `wrapper.ntservice.additional.<n>`,
      `wrapper.tmp.path`, `wrapper.ntservice.account`/`wrapper.ntservice.password` and
      `wrapper.ntservice.starttype` before `-i` (recovery actions have no key until P9). Then:
      what changes, how to read the warnings, differences (§3.4), SCM recovery interplay (R6), one
      JVM option or argument per key (`-Xms1g -Xmx2g` in one value is one argument, D19).
- [ ] P8.3 Run the owner's real configuration(s) end to end on Windows; close gaps; matrix updated.
- [ ] P8.4 *(recommended)* Release process: version tag, CI builds and attaches the zip to a GitHub
      release.
- [ ] P8.5 Complete `README.md`.
Done when: a YAJSW deployment is switched to nyajsw with no configuration edits (warnings only).

### P9 — Backlog (not scheduled; pick up only when asked)
- JVM discovery keys (`wrapper.java.command.minVersion/maxVersion/jreOnly/jdkOnly/preferJre/preferJdk`).
- `wrapper.java.customProcName`; `wrapper.java.initmemory.relative` / `maxmemory.relative`;
  `wrapper.priority`, `wrapper.affinity`; `wrapper.console.title` (`Kernel32.SetConsoleTitle` from jna-platform),
  `wrapper.console.visible`.
- `wrapper.filter.trigger.N` + YAJSW `wrapper.filter.action.N` (`RESTART`/`SHUTDOWN`/`NONE`, default
  `RESTART`; P1.10).
- `wrapper.stop.conf` (stop program; the reference's examples also write `wrapper.stop`) as a
  `command` stop strategy; `console` stop strategy via
  `GenerateConsoleCtrlEvent` with `SetConsoleCtrlHandler` shielding the wrapper.
- SCM failure actions (`ChangeServiceConfig2` with `SERVICE_CONFIG_FAILURE_ACTIONS` **and**
  `SERVICE_CONFIG_FAILURE_ACTIONS_FLAG`) driven by `wrapper.ntservice.failure_actions.*` (confirm
  YAJSW key names).
- Windows Event Log reporting of start/stop/failure; `PAUSE`/`CONTINUE` controls.
- `jmx` probe; `wrapper.restart.reload_configuration`; `wrapper.java.monitor.*`.
- POSIX support: `Platform` implementation generating a systemd unit, `wrapper.daemon.*` mapping.
- `wrapper.app.account`/`wrapper.app.password` (run the application as another user).

### Deferred review findings
Minor findings from pre-commit reviews that were consciously not fixed in the reviewed commit. Each
entry names the commit/plan item it came from; remove entries when addressed.
- PR review of Phase 7: a readiness check still running when the startup timeout ends its
  invocation may still reach a `log` probe listed after a failing one and reset its state; the new
  invocation's next check reads again from its launch, so nothing is lost, only work repeated.
- P7 end-to-end test: `AgentStopStrategy` logs the agent's acknowledgement from its own thread, so in
  theory a session closed by the exit just before it reads `STOPPING` leaves no line and fails the
  test's assertion on it; the window is tiny. The test overrides `config()`'s
  `wrapper.shutdown.timeout` by writing the key again (last value wins, with a warning).
- P7.4: a service whose first start never succeeds stays *starting* and cannot be stopped through
  the SCM (1061) until `wrapper.max_failed_invocations` is used up, and never with
  `wrapper.startup.timeout=0`; the guide warns about it, but accepting a stop while starting may be
  better. README.md does not list the guides yet (P8.5).
- P7.3c: `Supervision.launch` closing the agent's listener when the build or the start of the
  command fails is not tested (it needs a hook into the listener's life); the options and the
  session are, through console and service mode. A `jlink` runtime is recognised only by its
  `release` file; one started through a script (`wrapper.java.command`) is not looked into.
- P7.3b: `AgentChannel` is a final class, so probe and strategy tests use real loopback sockets
  with `FakeAgent`; extract an interface if a test needs a pure fake. `AgentChannel.exchange`
  recomputes its read timeout per line, not per byte (only an authenticated agent that misbehaves
  could stretch it). The shaded `nyajsw.jar` of P8.1 must not carry the agent's `Premain-Class`,
  or `AgentJar` would inject the whole wrapper; check the jar's contents too then.
- P7.3a: the jar's real manifest is not tested (`AgentJarTest` builds its own from the compiled
  classes), so a typo in the POM's `Premain-Class` would pass every test; check the packaged jar
  in `nyajsw-dist` (P8.1). A challenge-response would keep the token off the wire; the P7.3b
  constraints make the one-way handshake safe enough for a loopback channel.
- P7.2c: `OutputLines` keeps 10,000 lines and 2 million characters per invocation, filled whether
  or not a `log` probe reads the captured output; an application that logs more than that per
  liveness interval pushes its heartbeat out before the check sees it (the check fails, saying how
  many lines it missed). Matching push-style in the pump (a "matched since the last check" flag per
  probe), or filling the buffer only when a captured-output `log` probe is configured, would lift
  both the bound and the retention. `beforeLaunch` reads the log file's size on the supervisor's
  thread, which a hanging file share would block.
- P7.2b: a `command` probe's process is not in the application's kill-on-close job, so one that
  hangs while the wrapper is killed survives it; pass the job to probes if that matters. The
  command stop strategy (P3.4 entry) will need the same mechanics (argument array, no shell,
  discarded output, closed input, timeout, tree kill): extract a small runner from `CommandProbe`
  then, and decide one environment rule for both (the probe uses the wrapper's environment, not
  `wrapper.app.env.*`). `StateWait` still formats its durations itself (`… ms`), not through
  `log.Durations`. `ConfigDump` shows `wrapper.readiness.command` whole, arguments included.
- P7.2a: a `file` readiness probe passes at once on every restart when the application leaves its
  file behind (a crash does), so restarts are judged ready and `wrapper.max_failed_invocations` is
  undermined; decide before P7.4 between "modified since the launch" (`ProbeContext` would carry
  the launch instant) and deleting the file before each launch. The `HttpClient` of each `http`
  probe is never closed (one selector thread each until garbage collection); close probes in
  `Supervision.close()` if probes get a lifecycle. `ConfigDump` shows `wrapper.readiness.http.url`
  whole, user information included, since it is no registered secret; mask user information there
  before a debug dump uses it. Probe constructors trust `ProbeConfig.from` for ranges (a port
  outside 1–65535 would fail inside `check()`).
- P7.1: an unknown probe type, or one listed without its required key (`tcp` without
  `wrapper.readiness.tcp.port`, P7.2a), is found only when supervision is set up, so `-i` installs a
  service that then fails with exit code 2 on every start, and the error has no `file:line`.
  Validate the type lists against the registry in a check that `-c`, `-i` and `-t` share, with the
  key's location. The configured liveness interval, failures and action are not asserted end to
  end through `Supervision` (only the unknown type is). A startup timeout that fires after the
  process ended but before its exit is reported (the output drain, up to 2 s) still terminates
  and restarts it instead of letting the exit action decide; liveness ignores that window. After a
  liveness `RESTART` (as after a startup timeout) that reaches `wrapper.max_failed_invocations`,
  supervision ends `FAILED` without waiting for the terminated application, so console mode may
  exit before its pid file is removed. `ConfigValues.names` detects a secret by its text, so a
  short password that occurs in a type name (such as `process`) discards the list (warned).
- P1.2: `KeyPattern.commonExample` only finds overlaps with one segment per placeholder; free
  placeholders that span several segments in different shapes can still overlap undetected. No
  declared key is affected; revisit if a template with two free placeholders is added. Since P1.11
  `<n>` also spans segments, so the same holds for index templates, and a free placeholder right
  before `<n>` takes as many segments as it can (`wrapper.x.<name>.<n>` reads `wrapper.x.a.2.1` as
  `a.2` and `1`); no declared template has that shape.
- P1.11: with string indices a misspelt suffix (`wrapper.filter.script.2.agrs`) or a Tanuki key
  under an index family that is not declared yet is a family member, not an unknown key, so it gets
  no warning and its value is used. YAJSW, which reads families by prefix, probably does the same;
  declare more Tanuki keys when real files show them.
- P1.9: the default fallback for files that are not valid UTF-8 is a fixed windows-1252; using the
  JVM's `native.encoding` (the Windows ANSI code page, Java 17+) would read files from non-Western
  installations correctly without `wrapper.conf.encoding`. Decide with real files (Q1).
- P1.6b: no range checks: `wrapper.java.maxmemory=-5` is accepted. Add min/max to `KeySpec`
  (checked by `ValueParser`) when the consuming phases implement those keys. P7.1/P7.2a check the
  probe intervals and timeouts, `wrapper.liveness.failures` and `wrapper.readiness.tcp.port` where
  `ProbeConfig` reads them (`ConfigValues.problem`); move them to `KeySpec` with the others.
- P1.6b: `ProbeConfig` is one flat record; split it into per-probe settings registered with the
  probe types (AGENTS.md §4.4). (`wrapper.ping.*` moved there and the agent's defaults follow
  `wrapper.image` since P7.3c.)
- P1.6a: `SecretMasker` hides secrets by matching their text, so a short or common secret also masks
  unrelated text and the mask's position can hint at it (password `Java` shows as `C:\****\bin`).
  Tracking during interpolation which values were built from secret keys (or from names a secret
  referred to) would avoid both; do it if real configurations show the problem. Since P4 also
  masks stack traces, a short secret spoils them more (a password `at` hides every `at` of every
  frame).
- P1.3: no bound on interpolation depth or expanded size; a very long reference chain overflows the
  stack and doubling chains grow exponentially. Low risk (files are written by administrators); add
  limits if real configurations show the need.
- P1.10: Groovy and keystore errors hide the expression only when the key that owns the value is
  secret, not when it is reached from a secret key (`wrapper.ntservice.password=${pw}` with
  `pw=${keystore svcpass}` can name `svcpass` when `pw` is first resolved through the password).
  No value leaks, only the keystore entry name; check the reference chain as `cycle()` does if it
  matters.
- P2.5: when the configuration has errors only the errors are reported; the warnings gathered while
  loading (a misspelt key, for example) would often explain them. `ConfigException` would have to
  carry them.
- P2.5: usage messages and "not implemented yet" go to standard output with everything else; standard
  error would suit scripts better.
- P3.6: Launch errors that cannot fix themselves (a missing executable or working directory) are
  retried like any failed invocation: with the defaults about 20 s and five error lines before
  exit 1, where P2.5 failed at once; consider a
  kind of `LaunchException` that is not retried, or say it in the migration guide (P8.2, which
  should also mention that invalid `on_exit` values now stop the wrapper). The
  clamp in the `forceStop` message (a real timer fires late) is covered only by `MainTest`'s real
  timing; a fake scheduler that fires late would test it. `ApplicationProcess.waitFor()` and
  `waitFor(Duration)` are now used only by tests.
- P3.4: stop-strategy factories get only `LifecycleConfig`. P7's `agent` needs the invocation's
  agent channel and `command` a process launcher, the environment and a background executor; give
  the registry those dependencies then (e.g. `defaults(StopDependencies)`), and cancel a stop
  command still running when the stop ends. `StopStrategyRegistry` and `ProbeRegistry` share their
  shape (name → factory, named entries) but differ in input and contract; extract a common
  `NamedFactories` if a third registry appears. In the Tanuki format a `wrapper.shutdown.timeout` or
  `wrapper.jvm_exit.timeout` of 0 means "wait forever"; nyajsw reads a budget of 0 as "terminate at
  once", since a service stop must end; unconfirmed for YAJSW (§10 Q1).
- P3.3: every list key whose items are echoed must refuse a value holding a secret whole (list
  items cut a secret into pieces the masker does not know); `wrapper.stop.strategy` and the probe
  type lists do (`ConfigValues.names`, P7.1). The probe factories will change when `ProbeConfig`
  is split per probe (see the P1.6b entry).
  `ProbeContext` hands probes the whole `RunningApplication`, `terminateTree()` included; a
  narrower view would suit probes that are not ours. The meaning of `wrapper.startup.timeout=0`
  (no limit) comes from the Tanuki format and is unconfirmed for YAJSW (§10 Q1).
- P3.2: under `LOOSE`/`WRAPPER` the application "keeps running" after the wrapper ends, but its
  standard output and error are pipes the wrapper owns, so it loses them (and may fail writing to
  them), and in console mode it shares the console and its Ctrl+C. P6.2 left it as is (a
  service has no console); P6.4 keeps it out of the job (none under `LOOSE`/`WRAPPER`). Still
  open: redirect its output and detach it from the console, so that it really survives the
  wrapper.
- P4.3a (review): done in P6.2/P6.3: service mode opens the configured log with the bootstrap log
  for its problems and uses the bootstrap log before the configuration is read, opened only when
  there is something to write, beside the configuration file instead of the temporary directory
  (for `LocalSystem` `C:\Windows\Temp`, where any user may create a file of that name first, or a
  link). Left: `%TEMP%` is still the fallback for a configuration path that cannot be parsed and
  for an account that may not create files beside the configuration (for `LocalSystem`, which
  rarely lacks that right, `C:\Windows\Temp`), and the file is opened following links.
  The bootstrap log masks no
  stack trace (it may run before any secret is known): once the configuration is read, log
  exceptions only to the configured log, or give `bootstrap` the configuration's mask. Three debug
  messages put exception text into the message unmasked (`Supervisor` cancelling a timer,
  `OutputPump` and `ApplicationProcess` on a closed pipe); they carry I/O errors, not values, but a
  `debug(…, Throwable)` would let them attach the exception instead (and `Main.unexpected` drop
  its own masking).
- P4.3: the file name in messages is masked as text, while a
  secret copied into `wrapper.logfile` reaches the path normalised (`/` turned into `\` on Windows,
  `.` and `..` resolved), so a password such as `pa/ss` would not be recognised; register the
  normalised form with `SecretMasker` if secrets in log file names ever matter. A roll that is
  interrupted (the interrupt flag of the logging thread set) gets no retries.
- P4.3b: the start roll of the `JVM`/`WRAPPER` modes and the first `maxdays` clean-up happen when
  the file sink opens, so any process that opens the configured log rolls the live file. Console
  mode and (P6) the service do; the service commands of P5.4 report on the console only, and any
  later command must do the same (or open the sink without the start roll, e.g. through an
  explicit `wrapperStarting()`), or it would try to move a running service's log. The `DATE`
  clean-up lists the directory and retries
  deletes while holding the sink's lock, which can block the application's output for a moment
  at the first record of a day; move it off the lock if that shows. Under `DATE` the start day's
  file is created even when the first record falls on the next day.
- P4.1 (PR review): declaring `wrapper.debug.level` as text makes `COMPATIBILITY.md` show its type
  as text; a registry flag that leaves the type check to the key's view would keep "integer". The
  same conditional rule would suit `wrapper.logfile.maxdays`, which only warns without `DATE` but
  whose non-numeric value is still an error through the validator's type check.
- P5.3 (review): `ServiceCommandLine` repeats two small pieces of `JavaCommandBuilder` (the
  `bin\javaw.exe` default and the `;` separator), and the D19 unquoting it shares lives as a public
  static on the builder; a small `launch` helper could hold both if a third caller appears. A bad
  `wrapper.ntservice.additional.N` fails as a `LaunchException` (exit 1); P5.4 may want to report it
  as a configuration error. The stored binary path is the unexpanded string, so whether the SCM
  expands `%NAME%` at start (the warning assumes it) can only be confirmed once P6 starts a service.
- P5.2 (review): `platform.windows.StateWait.Pause` repeats `log.RollingFileSink.Pause` (a pause
  that tests replace); extract one shared type when a third user appears. `WindowsServiceController`
  reads `lifecycle.Supervisor.longestStop`, a new edge from `platform` to `lifecycle` that the P6
  service host needs anyway. P5.6 reads the installed configuration back from the registry instead
  of `QueryServiceConfig`/`QueryServiceConfig2`, which `ScmApi` does not have; `-q` will need them
  to show configuration. System error texts are localised, so integration tests must not compare
  them.
- P5.4 (review): the service commands report through `WrapperLog.console`, the log for before
  the configuration is read: it masks nothing itself (each call site masks), ignores
  `wrapper.console.format`/`loglevel` and never shows debug output. A console-only log built from
  `config.log()` without the file sink would fix all three. Check what YAJSW's `-r` does with a
  running service (Tanuki's stops it first); nyajsw leaves it to Windows, so `-r` then `-i` on a
  running service fails with 1072 until it stops.
- P5.5 (review): the grant of the logon right is silent; `-i` could say it (a notice next to the
  returned warnings). A domain GPO that defines "Log on as a service" overwrites the local grant at
  the next policy refresh, and 1069 comes back later, as with the Services console: say so in the
  user guide. Built-in accounts are recognised by spelling; comparing the looked-up SID with the
  well-known ones would also catch localised names (`NT-AUTORITÄT\...`). `JnaLsaApi` borrows the
  system-text helper of `JnaScmApi`, which uses the shared `Win32Failures` since P6.4.
- PR #7 (review): in `WindowsServiceController.install` the logon grant runs inside the
  `catch (RuntimeException)` that deletes a half-installed service; a `LinkageError` from the
  first use of the LSA binding skips that clean-up, and `Main.service()` shows its stack trace.
  Unlikely (`advapi32` is a system library); catch it there like `call()` does.
- P6.1 (review): `STOP_PENDING` accepts no controls, so an operator stop that is still running
  when the system shuts down gets no `PRESHUTDOWN` and only `WaitToKillServiceTimeout`; P6.2 kept
  that (the body has nothing to do with a second stop); revisit if P6.5 shows such stops cut
  short. Not tested: a stop control that arrives between the handler's registration and the first
  `START_PENDING`, which Windows cannot send (nothing is accepted yet); the final `STOPPED` is not
  retried before `run` gives up.
- P6.4 (review): without `JOB_OBJECT_LIMIT_BREAKAWAY_OK`, a helper the application starts with
  `CREATE_BREAKAWAY_FROM_JOB` cannot leave the job; decide when a user needs it (until then
  `LOOSE`/`WRAPPER` or a helper started outside the tree). With a script as `wrapper.java.command`,
  `cmd.exe` may start the JVM before it is assigned, which leaves the JVM outside the job.
  `Supervisor.abandonUnready` logs "giving up on it", while the job now ends it with supervision.
  Not tested: that killing the process that holds the job handle ends the application (a helper
  JVM that creates the job and is then killed would show it), that the console-mode run under a
  real job logs no job error, and the order of the job's end against the walk of the tree.
- P6.3 (review): a bare `wrapper.java.command` (`java`, YAJSW's default) is found by Windows in
  the wrapper's own JDK first, before `PATH` (`CreateProcess` searches the parent's directory
  first), so the application runs on the wrapper's Java 21; the `ApplicationProcess` Javadoc says
  `PATH`. The guide says to give the command with its directory; resolving bare names against
  `PATH` in the wrapper would match the documentation. The access hint names `user.name`
  without its domain, and `-Duser.name` in `wrapper.ntservice.additional.<n>` would change it;
  P6.5 should show what it is under `LocalSystem`. It covers the log file at open and the pid
  files, not a later roll or write failure of the log.
- P6.2 (review): a stop with the default chain (`terminate` alone) waits the whole stop budget
  (`wrapper.shutdown.timeout`, default 30 s), during which nothing asks the application to end,
  since a service receives no Ctrl+C; service mode says so at start. Terminating at once, or a
  graceful default, is for P7 (the agent's `STOP`) and the `command` strategy (P1.2 gap). A
  failure to register the control handler names the service but reaches only the console (none
  under the SCM): `ServiceMode` learns the name only once the service runs; the host could hand
  it over. `wrapper.tmp.path` cannot steer `jna.tmpdir` in service mode, since JNA is loaded
  before the configuration is read: the installed command line carries it (D12). The `--service`
  switch is spelled in `Main` and in `ServiceCommandLine`; a test that ties the two would keep
  install and dispatch together. The console line `supervision ended …` now comes after the
  wrapper's pid file is removed (cosmetic).
- P1.2 (plan gap): `wrapper.stop.command` and the `command` stop strategy (`CommandStopStrategy`,
  §4.6) are registered for P7 but no P3/P7 checklist item implements them yet; schedule them when
  P7 is planned in detail.

## 6. Testing strategy and CI

* **Unit tests (TestNG)** run on Linux, macOS and Windows with no privileges. Deterministic:
  `Clock`/ticker injection, fake processes, in-process servers for probes, temp dirs for files and
  logs. Mockito mocks our own `ScmApi`/`ServiceHost` facades, never JNA classes. Hand-written JNA
  structures get layout tests that run everywhere.
* **Windows integration tests** (TestNG group `windows-integration`, Maven profile of the same
  name): real SCM, throwaway service names with random suffix, guaranteed cleanup, wrapper log
  attached to the test report on failure. Run locally from an elevated shell and on the
  `windows-latest` CI job.
* **Golden tests** for command-line construction (argument arrays) and log formatting, using fixture
  `wrapper.conf` files (including anonymised real ones).
* **Generated-doc test**: `docs/COMPATIBILITY.md` must match the key registry.
* **CI** *(recommended)*: GitHub Actions matrix `ubuntu-latest` (unit) and `windows-latest`
  (unit + integration), Java 21 (Temurin), Maven cache. Releases built from tags (P8.4).
* **Coverage expectation** (not a gate): every key, probe, stop strategy and state transition is
  exercised at least once.

## 7. Configuration compatibility matrix

The matrix is generated from the key registry into [COMPATIBILITY.md](COMPATIBILITY.md) (P1.7) and
must not be edited by hand; a test fails when it is stale (AGENTS.md §3 has the command that
rewrites it). The hand-written initial version that used to live here seeded the registry in P1.2;
`ConfigKeysTest.seededKeys` keeps its keys and statuses under test. Change a key's status, default
or notes in `ConfigKeys` and regenerate the document in the same commit.

## 8. Risks and mitigations

| # | Risk | Mitigation |
|---|---|---|
| R1 | SCM 30-second start window vs JVM start-up on cold machines | Call `StartServiceCtrlDispatcher` before any other work; report `START_PENDING` immediately; `javaw`, small classpath, no eager initialisation. |
| R2 | JNA callbacks garbage-collected, or a slow control handler | Static references to callback objects; the handler reports `STOP_PENDING`, queues the event and returns; documented in place. |
| R3 | JNA native library extraction fails under service accounts / antivirus | `-Djna.tmpdir` baked into the service command line from `wrapper.tmp.path`; documented fallback shipping `jnidispatch.dll` with `jna.boot.library.path`. |
| R4 | No generic graceful stop on Windows (`destroy()` is `TerminateProcess`) | Agent stop strategy on by default once P7 lands; `command` strategy; `console` strategy in backlog; `terminate` kills the whole tree, always last. |
| R5 | Parsing differences from YAJSW (backslashes, encoding, `include =`, `${sys:}`/`${env:}`, Groovy) | Real configuration fixtures before P1 decisions; explicit D7 (lists each deviation from Commons Configuration found in P1.1); loud warnings; migration guide. |
| R6 | Double restart, or none: SCM recovery and wrapper restart interplay | Wrapper only exits with `ERROR_SERVICE_SPECIFIC_ERROR` after the restart policy gives up; `SERVICE_CONFIG_FAILURE_ACTIONS_FLAG` set when failure actions are configured; documented in the migration guide. |
| R7 | Classpath wildcard ordering differs from YAJSW | Deterministic sort, documented; fixtures from real deployments to check behaviour. |
| R8 | Log rotation on Windows (locked files, rename failures) | Single writer, rename with retry/fallback, tests on Windows CI. |
| R9 | Hosted Windows CI runner not elevated or flaky SCM | Verify in P0.3; integration tests retry transient `MARKED_FOR_DELETE`; unit coverage does not depend on them. |
| R10 | Licensing contamination | Clean-room rule in AGENTS.md; only public key names reused; reviewers check for copied text. |
| R11 | Unverified YAJSW defaults/key names (marked `Unverified` in the registry, "*Verify:*" in the matrix) | Defaults, values and names of registered keys were checked against the public reference on 2026-11-06, and missing YAJSW keys listed (P1.10); what it leaves open is confirmed against the owner's real files/`doc` folder before implementing each key; record the outcome in the registry. |
| R12 | Windows argument quoting (`ProcessBuilder` re-quoting, embedded quotes) | Policy D19 with tests (P2.2); golden tests on argument arrays. |

## 9. Decisions log (ADR-lite)

| ID | Decision | Rationale | Status |
|---|---|---|---|
| D1 | Clean-room reimplementation, Apache-2.0; compatibility only at the configuration/CLI surface | The owner is moving off YAJSW (§10 Q9); YAJSW lists Apache-2.0 alongside LGPLv3, so a clean room avoids licence entanglement and inherits none of its legacy design | Accepted |
| D2 | Wrapper requires Java 21; agent compiled to Java 8 bytecode with zero dependencies (tests on 21) | Wrapped applications may run old JVMs; agent is injected into them | Accepted |
| D3 | Maven multi-module: `nyajsw-agent`, `nyajsw-wrapper`, `nyajsw-dist`; wrapper depends on agent for `AgentProtocol`, never the reverse | Separate compilation target for the agent; one definition of the protocol | Accepted (P0.4 build plugins, parent POM properties: clean 3.5.0, resources 3.5.0, compiler 3.16.0, surefire 3.5.6, jar 3.5.1, install 3.2.0, deploy 3.2.0, enforcer 3.7.0, dependency 3.11.0) |
| D4 | TestNG + Mockito (inline mock maker via `-javaagent` in surefire); Windows-only tests in group/profile `windows-integration` | Owner requirement; keep unit suite portable; avoid JDK 21 dynamic-agent warnings. Surefire stays on the 3.5.x line with an explicit `surefire-testng` provider: 3.6.x ships no `surefire-testng` and runs TestNG only through the JUnit Platform `testng-engine` bridge, which does not support `testng.xml` suites and turns TestNG's default reporters (`testng-results.xml`) off unless configured | Accepted (P0.4: TestNG 7.12.0, Mockito 5.24.0, surefire + surefire-testng 3.5.6) |
| D5 | JNA + `jna-platform` for SCM calls; our own `ScmApi` facade; hand-written bindings in `platform.windows.jna` for delayed-auto-start/pre-shutdown structures, job objects, console functions and LSA; `W32ServiceManager`/`W32Service` not used (no create/delete, own exception type, not mockable) | Reuse maintained generic bindings where they exist; mockable boundary; known gaps listed up front | Accepted (P0.4: JNA and `jna-platform` 5.19.1, managed in the parent POM; added to `nyajsw-wrapper` in P5.2) |
| D6 | Logging on `java.util.logging` with a custom rolling handler; no SLF4J/Logback | Zero deps; exact control over `wrapper.logfile.*` semantics | Accepted (P4.3: `RollingFileSink`, a JUL `Handler`) |
| D7 | Custom line parser (§4.5); odd-trailing-`\` continuation, skipping blank/comment lines inside it; in file values a backslash before `\ , " ' # = : !` or a space stands for that character (YAJSW's escapes, as the owner's Denodo files show; P1.9), every other backslash literal (YAJSW would read `\t`, `\n`, `\r`, `\f`, `\b`, `\uXXXX` as control characters and Unicode); UTF-8 with BOM skipped, windows-1252 fallback with a warning, UTF-16 rejected unless declared through `-Dwrapper.conf.encoding`; `wrapper.conf.encoding` decoded strictly; `#include` (optional: missing file warns), `#include.required` and case-insensitive `include =` (required); `${sys:}`/`${env:}` prefixes. Deliberate deviations from Commons Configuration: only `=` separates key and value (no `:`/whitespace separator); no comma splitting of values, `include` included; nested includes resolve against the including file first; a value continued at end of file is kept with a warning instead of dropped; UTF-8 files are read as UTF-8 (Commons Configuration defaults to ISO-8859-1; YAJSW defaults to the platform charset, i.e. the JVM default, UTF-8 on Java 18+ per JEP 400, else the ANSI code page), so non-ASCII text may now read differently, and on non-Western Windows (windows-1251, MS932, GBK) a file without `wrapper.conf.encoding` falls back to windows-1252 with a warning; a declared encoding is decoded strictly where YAJSW's reader probably replaced bad bytes silently | Windows paths dominate; match what YAJSW's Commons Configuration parser accepted; avoid `java.util.Properties` escape surprises; never silently drop input | Proposed (P1.1 implemented without real files; confirm with §10 Q1). Evidence from YAJSW's public parameter page (2026-11-06) that differs from this decision, to be resolved in P1.2/P1.3 with real fixtures: it names `=` as the separator, `,` as a list delimiter (escaped `\\,`) while advising against lists, backslash as an escape character with `/` preferred in paths, `include=` as an ordinary property whose value may contain `${...}`, environment names lower-cased in `${...}`, and encodings set by `wrapper.conf.encoding`, defaulting to the platform charset. Follow-ups: verify the backslash and lower-cased-environment behaviour against a real file; `${...}` in include paths is supported since P1.3 and `wrapper.conf.encoding` since P1.9 |
| D8 | Single key registry drives parsing, validation, warnings and generated docs. Keys are `public static final KeySpec` constants in `ConfigKeys`, collected into one immutable `KeyRegistry`; derived defaults are declared there by description and computed by the typed view that owns the key (P1.6), or by the loader for `wrapper.conf.encoding`, because they depend on typed values (e.g. `wrapper.agent.enabled`) or the configuration file's location rather than on raw strings | One source of truth; no drift between code and docs; constants let code refer to keys without repeating names | Accepted (P1.2) |
| D9 | Extensions live under `wrapper.*`, tagged `NYAJSW` in the registry | One namespace for users; provenance still visible | Accepted |
| D10 | Agent enabled by default; loopback + random token passed through the environment, port through `-D` | Graceful stop and heartbeat need it; YAJSW also injected code by default; token never on a logged command line | Accepted (§10 Q3, 2026-11-08) |
| D11 | groupId and base package `io.github.jacobosanchez.nyajsw` | Matches the GitHub owner; Maven Central-compatible | Proposed (§10 Q2) |
| D12 | Service binary path = installing JVM's `javaw.exe` + classpath (default `java.class.path`) + `wrapper.ntservice.additional.N` (YAJSW's key for these options) + `-Djna.tmpdir` + `--service <conf>`; `wrapper.java.command` only affects the application JVM, `wrapper.ntservice.java.command` (YAJSW: default the application's Java command) may replace the installing JVM's `javaw.exe` if it is a Java 21; it must be given with its directory, and one known to be older than 21 is an error at install rather than a silent fallback to the installing JVM (P5.3) | Wrapper needs Java 21 and JNA settings before configuration is read; works from unpacked builds and the distribution | Proposed |
| D13 | The default working directory and relative wrapper-side paths (log file, pid files, temporary directory, probe files) resolve against the configuration file's directory; relative application-side paths (jar, library path, image, a Java command with a directory, classpath entries) resolve against the working directory, as the application sees them (P1.6b) | Services start in `System32`; configuration-relative is predictable for the wrapper, while the application's own paths must agree with what the child JVM resolves (usual YAJSW layout: `conf/wrapper.conf` with `wrapper.working.dir=..`) | Proposed (verify YAJSW, P1.9) |
| D14 | Start type default `AUTO_START`; enum aliases accepted | Match YAJSW defaults and spellings seen in the wild | Accepted |
| D15 | Probe SPI, probe registry and stop-strategy registry are introduced in P3 with `process`/`terminate`; later probes and strategies are additions | Avoid rewriting the supervisor when P7 lands | Accepted |
| D16 | Default liveness probe is `agent` when the agent is enabled (`wrapper.ping.*` semantics), none otherwise | YAJSW pings by default; keep behaviour parity without forcing the agent | Accepted (P7.3c): `wrapper.ping.interval` is the liveness interval when `wrapper.liveness.interval` is not written; a written `wrapper.ping.timeout` is the silence that counts as a hang (pings of up to one interval, as many failed in a row as fit, unless `wrapper.liveness.failures` is written; otherwise it is each ping's timeout); 0 for either is no heartbeat; a run without the agent passes the probe |
| D17 | Migration path (design only; not pursued, §10 Q8 chose a reinstall): ship a thin alias class named like YAJSW's service booter (`org.rzo.yajsw.boot.WrapperServiceBooter`, a deliberate exception to D11 because existing service registrations store that name) that detects service mode when `--service` is absent and delegates to the normal entry point; a migration command rewrites an existing service's binary path to a Java 21 `javaw.exe` and our classpath (D12), since a bare jar swap cannot work when the old registration names an older JVM; `-D` options on the wrapper command line (e.g. `-Dwrapper.tmp.path`, a `jna_tmpdir` property, `-Dpath`) feed the system-property layer of §4.5; we also set the real `jna.tmpdir` from `wrapper.tmp.path` (R3). Short CLI options already exist in §3 | Observed in Denodo Platform's public KB (not tested by us): its services are registered as `java.exe … WrapperServiceBooter <conf>`, installed with `-i <conf>`, and re-pointed with `sc config` plus `-D` flags for non-admin accounts. Whether the underscore-spelled `jna_tmpdir` is read by YAJSW or JNA is unknown | Deferred (§10 Q8, 2026-11-07; P5.4a dropped). Already in place: `-D` options before the main class feed the system-property layer (§4.5) and `jna.tmpdir` follows `wrapper.tmp.path` (P5.4); the underscore-spelled `jna_tmpdir` is not mapped to `jna.tmpdir` |
| D18 | The registry declares the YAJSW keys its public reference documents as supported by YAJSW, each with a nyajsw status; Tanuki keys that the reference lists as not (yet) supported by YAJSW (e.g. `wrapper.disable_restarts`, `wrapper.jvm.port`, `wrapper.ntservice.pausable`) stay undeclared and get the generic unknown-key warning (keys seeded earlier, such as `wrapper.syslog.*`, keep their entries). Defaults follow the reference where it states one (§3.3), including `wrapper.on_exit.default=SHUTDOWN` | A YAJSW file can only rely on keys YAJSW honours; declaring Tanuki-only keys would suggest a compatibility YAJSW never had. Following YAJSW's defaults keeps migrated services behaving as before | Accepted (P1.10) |
| D19 | Arguments of the application are passed as an array. One pair of double quotes around a whole argument, or around everything after its first `=` (`-Dx="a b"`, `-XX:OnError="cmd /c x"`), is removed, as a shell would; any other double quote is a launch error naming the key family, with the argument masked | On POSIX every argument reaches the application unchanged, quotes included; on Windows `ProcessBuilder` wraps an argument with blanks in quotes, so `-Dx="a b"` would become `"-Dx="a b""` and break the command line, and an embedded quote is passed on unescaped (`jdk.lang.Process.allowAmbiguousCommands` defaults to true on JDK 21), which breaks it as well; checked by running `java.exe` through `ProcessBuilder` on JDK 21.0.13, which also handles trailing backslashes and empty arguments itself. Dropping a stray quote would change the value | Accepted (P2.2) |
| D20 | `wrapper.control` is honoured in both directions. Application ends → wrapper ends under `TIGHT` and `WRAPPER`, so there the exit action `STOP` ends the wrapper like `SHUTDOWN`; only under `LOOSE` and `APPLICATION` does `STOP` keep the wrapper running without an application until it is stopped. Wrapper stopped → application stopped under `TIGHT` and `APPLICATION`; under `LOOSE` and `WRAPPER` the application is left running (logged with its pid). Launch errors count as failed invocations and are retried, also those a retry cannot fix (a missing Java executable is logged `wrapper.max_failed_invocations` times, `wrapper.restart.delay` apart, before `FAILED`). `wrapper.max_failed_invocations=N` means N failed invocations in a row end supervision, so at most N-1 restarts follow quick failures; with 1 there is none | YAJSW's reference (read 2026-11-06/07) defines the four couplings that way and `STOP` as "stop the application but not the wrapper"; its change log ties `STOP` to `wrapper.control`, and this is the reading that coupling implies. The reference counts launches that fail towards `wrapper.max_failed_invocations` and gives its default as "5 invocations", though its "maximum number of times … to restart" also allows N restarts | Proposed (P3.2; the coupling itself is documented, its effect on `STOP` is our reading; confirm with YAJSW 13.18 or real use, §10 Q1) |
| D22 | `wrapper.logfile.rollmode` takes YAJSW's `JVM`, `WRAPPER`, `DATE` and Tanuki's `SIZE`, `NONE`, `SIZE_OR_JVM`, `SIZE_OR_WRAPPER` (the last two as aliases of `JVM` and `WRAPPER`); `wrapper.logfile.maxsize` rolls in every mode but `NONE`, `JVM` included; `WRAPPER` rolls at wrapper start only | YAJSW documents `JVM` (no size limit) and `DATE`, and names `WRAPPER` without describing it; files derived from Tanuki templates carry its values, and an error would break the "swap the wrapper, not the configuration" goal. Keeping size rolling under `JVM` honours a configured size and keeps a restarting application from filling the disk | Proposed (P4.3b; confirm against YAJSW 13.18, §10 Q1) |
| D21 | In console mode the shutdown hook (Ctrl+C, closing the session) stops through the supervisor, waits at most the stop budget + 10 s + 10 s, then ends the JVM with `Runtime.halt(<exit code>)`; `halt` is allowed only there, beside `System.exit` in `Main` | Once a signal has begun the JVM's shutdown its exit code is the signal's (130, `0xC000013A`) and `System.exit` blocks; `halt` is the only way to return the application's code, as §3.2 promises. It skips hooks that have not run yet, which nyajsw does not rely on; the console is flushed first (a file log, P4, must be flushed too) | Accepted (P3.6) |
| D23 | Exit codes follow the exception type (`cli.ExitCode.of`): `ConfigException` → 2, `ServiceException` (the SCM refused or failed, P5.2) → 3, `UnsupportedPlatformException` (a `PlatformException` subclass: the host has no such operation) → 4, anything else → 1. A plain `PlatformException` (an OS call failed on a platform that has the operation, §4.4, such as a native library that cannot be loaded) is not an unsupported platform and ends as 1, also from a service command (P5.4) | §3.2 named `PlatformException` → 4, but §4.4 also uses `PlatformException` for Win32 failures of job objects and console functions, which must not read as "wrong platform" | Accepted (P5.1) |
| D24 | A liveness failure (`wrapper.liveness.failures` failed checks in a row) terminates the application at once instead of stopping it gracefully. `RESTART` then restarts it like an invocation that ended by itself, its run time measured up to when the last passing check began (readiness or liveness: the last time it was seen healthy), so that one unhealthy from the start reaches `wrapper.max_failed_invocations` however long the checks take; `SHUTDOWN` waits in `STOPPING` for it to end (at most 10 s) and ends supervision `FAILED` (console: the application's exit code, never 0; the service `STOPPED` with 1066), never `STOPPED` with success. Once the process has ended, checks no longer count: the exit action decides | An application that fails its checks may not answer a graceful stop either; YAJSW kills the JVM when its ping times out, and the startup timeout (P3.3) terminates the same way. A deliberate shutdown of an unhealthy application must not read as success, so that scripts and SCM recovery see it | Proposed (P7.1; revisit once the agent's graceful stop exists, P7.3) |
| D25 | Liveness reuses the per-probe settings of readiness: `wrapper.readiness.<type>.*` configures a probe type wherever it is listed, in `wrapper.readiness.type` or `wrapper.liveness.type`; there are no `wrapper.liveness.<type>.*` keys | The common case checks the same endpoint for both; one set of keys keeps the surface small. Probing readiness and liveness on different ports or URLs, or with different timeouts, is not possible; adding `wrapper.liveness.<type>.*` later would need a precedence rule (liveness key, else readiness key) | Proposed (P7.2a) |

## 10. Open questions for the project owner

1. **Real configuration files.** (*Partly answered 2026-11-06:* the Denodo VDP and Scheduler
   `service.conf` files are the `denodo-vdp` and `denodo-scheduler` fixtures; the scheduler web
   admin tool's file and YAJSW 13.18's `doc/` folder would still help.)
   Please add the current `wrapper.conf` (anonymised) as a fixture,
   together with the `conf/wrapper.conf.default` template and `doc/` folder of the YAJSW version in
   use (reading key names and defaults is within the clean-room rule), and tell us the Java version
   the application runs on (*answered 2026-11-06: Java 21*; the YAJSW version, 13.18, is recorded
   in Q9). This settles D7 and the unverified registry entries. In particular: does any file rely
   on `:` or whitespace as the key/value separator, on comma-separated lists in values (Commons
   Configuration's list delimiter, unless YAJSW disables it), or on `${...}` in include paths? What encoding are the files saved in? Does any service use
   `wrapper.on_exit.*=STOP` or a `wrapper.control` other than `TIGHT`, and how many restarts does
   YAJSW make with `wrapper.max_failed_invocations=1` (D20)? The target deployment is Denodo Platform, whose
   public KB shows `conf/vdp/service.conf`, `conf/scheduler/service.conf` and
   `conf/scheduler-webadmintool/scheduler-webadmin-service.conf` (generated from
   `setup/vdp/templates/conf/service.conf.template`); anonymised copies of your own installed
   `service.conf` files are the fixtures we need most. Do not add vendor templates unless their
   licence allows redistribution in an Apache-2.0 repository.
2. **Package/groupId** `io.github.jacobosanchez.nyajsw` acceptable? (D11)
3. **Agent injection by default** acceptable for your application? It adds one `-javaagent` option
   to the child JVM. (D10) (*Answered 2026-11-08:* yes, on by default; `wrapper.agent.enabled=false`
   turns it off.)
4. **POSIX** needed in the foreseeable future? It changes nothing now but affects P9 priority.
5. **Windows versions and service accounts** used in production (LocalSystem, a domain account,
   LocalService)? Affects P5.5/P6.3 hardening and the test matrix.
6. **Windows Event Log** reporting wanted (backlog item)?
7. **CI and releases on GitHub Actions** wanted (P0.3, P8.4)? They are recommended, not requested.
8. **Takeover of existing services.** Should nyajsw be able to run under services that YAJSW already
   registered (same `WrapperServiceBooter` class name and `-i`/`-r` options), so that swapping the
   jar is enough, or is a reinstall with our own installer acceptable? Which service names, Denodo
   version and run-as accounts are in use? (D17) (*Partly answered 2026-11-07:* a reinstall is
   acceptable for now: stop (`-p`) and remove (`-r`) the service with YAJSW, then install it with
   nyajsw's `-i` on the same `wrapper.conf`; P5.4a is dropped. Run-as accounts remain open under
   Q5; service names and the Denodo version would still help P8.3.)
9. **YAJSW is still being released.** SourceForge lists yajsw-stable-13.x through 14.01
   (2026-09-13; Netty 4.2.17, commons-configuration2 2.15.1), and the project now lists
   Apache-2.0 alongside LGPLv3 (the licence change dates from 12.00). §1, D1, AGENTS.md and the
   README described YAJSW as unmaintained and LGPL (corrected with the answer below). Does the
   motivation still stand (for example Java 21 as the wrapper runtime, no Groovy, probes), and
   which YAJSW version do your services run? Defaults and parsing may differ between 12.x and 14.x.
   *Answer (2026-11-06):* the services run **yajsw-stable-13.18** (2025-10-05). Its readme warns
   that 13.16 and 13.17 are broken and announces that 14.0 needs JDK 11 or later, because the Groovy
   bundled with 13.x does not run on JDK 22 or later and Groovy 5 needs JDK 11. So 13.18 is in
   practice limited to JDK 21 or older, at least wherever its Groovy is loaded (not confirmed for
   setups without scripts). The 14.00 readme (2026-04-29) adds JDK 24+ support, confirms the JDK 11
   minimum and upgrades the core libraries, so the 14.01 library versions above (and in P1.9) need
   not hold for 13.18; the jar names in the 13.18 `lib/` folder settle that. 13.18 is therefore
   the reference for compatibility checks. The online reference is unversioned (its changes page
   stops at 12.06, §11), so where it and 13.18 may differ, the 13.18 `doc/` folder and real files
   decide (Q1). *Answer (2026-11-06), motivation:* it stands; the owner wants to move off YAJSW.
   The application runs on Java 21 (Q1).

## 11. References

* YAJSW configuration reference: https://yajsw.sourceforge.io/YAJSW%20Configuration%20Parameters.html
  (the key names in the registry and `docs/COMPATIBILITY.md` come from YAJSW's published default
  template as reproduced in third-party examples, hence the `Unverified` marks). The page was read in summarised form on 2026-11-06 and
  informed the D7 note. A check of the registered keys' defaults, values and names followed on the same day
  (results in P1.10 and §4.6–§4.8); the page states no defaults for many keys, which stay
  `Unverified`.
* YAJSW pages read alongside it: introduction (https://yajsw.sourceforge.io/), changes
  (https://yajsw.sourceforge.io/YAJSW-Changes.html, which stops at 12.06), migration from JSW
  (https://yajsw.sourceforge.io/Migrating%20from%20JSW.html), and the SourceForge release list
  (https://sourceforge.net/projects/yajsw/files/yajsw/, latest yajsw-stable-14.01, §10 Q9).
* Microsoft Learn — `SERVICE_PRESHUTDOWN_INFO` (default pre-shutdown timeout 10 s since build
  15063): https://learn.microsoft.com/windows/win32/api/winsvc/ns-winsvc-service_preshutdown_info;
  `SERVICE_FAILURE_ACTIONS_FLAG` (failure actions on `STOPPED` with a non-zero exit code, R6):
  https://learn.microsoft.com/windows/win32/api/winsvc/ns-winsvc-service_failure_actions_flag
* Denodo Knowledge Base — starting the Denodo Windows services without Administrator privileges
  (service names, `WrapperServiceBooter` command lines, a `jna_tmpdir` system property; basis of D17):
  https://community.denodo.com/kb/en/view/document/How%20to%20start%20the%20Denodo%20Windows%20Services%20without%20Administrator%20privileges
* Microsoft Learn — Service functions: https://learn.microsoft.com/windows/win32/services/service-functions
* JNA: https://github.com/java-native-access/jna (`com.sun.jna.platform.win32.Advapi32`, `Winsvc`, `Kernel32`)
* TestNG: https://testng.org/
