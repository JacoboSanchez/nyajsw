# nyajsw — Implementation Plan

Status legend: `[ ]` not started · `[~]` in progress · `[x]` done
Last updated: 2026-11-06

How to use this document: pick the next unchecked item in §5, implement it following
[AGENTS.md](../AGENTS.md), and update the checkbox, the decisions log (§9) and the compatibility
matrix (§7) in the same commit.

---

## 1. Summary

nyajsw is a clean-room replacement for YAJSW (Yet Another Java Service Wrapper). YAJSW is no longer
developed and its code base predates modern Java, yet it still runs production services through a
`wrapper.conf` file that many deployments depend on. nyajsw keeps that configuration surface (keys,
semantics, command-line switches and helper scripts) and reimplements the behaviour on Java 21,
using JNA to call the generic Windows Service Control Manager (SCM) API, with a cleaner internal
design and a test suite that runs on every platform.

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

* POSIX daemons (`wrapper.daemon.*`, `wrapper.update_rc`). The platform abstraction leaves the door
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
  `docs/COMPATIBILITY.md`; §7 is the hand-written initial version used to seed it.
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
* Extensions (probes, stop strategies, wrapper-JVM options, filter actions) are new `wrapper.*`
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
| `cli` | Argument parsing, command dispatch, exit codes. The only place in the wrapper that calls `System.exit`. | `Main`, `Command`, `ExitCode` |
| `config` | Parse, include, interpolate, layer and validate configuration; typed views; key registry. | `ConfigLoader` → `LoadedConfig` (with `ConfigLayer`), `ConfigSource` + `FileConfigSource` / `SystemPropertyConfigSource`, `ConfigEntry`, `ConfigLocation`, `SourceContent`, `Interpolator`, `ConfigKeys` (registry contents), `KeyRegistry` (lookup), `KeyCollector` (key families), `KeySpec`, `KeyPattern`, `KeyStatus`, `KeyOrigin`, `KeyType`, `KeyDefault`, `KeyCategory`, `Unverified`, `ConfigValidator`, `ValueParser`, `SecretMasker`, `ConfigDump`, `WrapperConfig` with `JavaConfig`, `ServiceConfig`, `LogConfig`, `LifecycleConfig`, `ProbeConfig` (records), `ConfigException`, `ConfigWarning` |
| `launch` | Build the application command line and run it. | `JavaCommandBuilder` (with a secret-masking loggable form), `ClasspathExpander`, `ApplicationProcess`, `OutputPump`, `LaunchException` |
| `lifecycle` | Supervision state machine and policies. | `Supervisor`, `State`, `ExitAction` (enum `RESTART`/`SHUTDOWN`/`STOP`), `ExitActionResolver`, `RestartPolicy`, `StopStrategy` (SPI) + `StopStrategyRegistry`, `TerminateStopStrategy`, `CommandStopStrategy`, `AgentStopStrategy`, `PidFiles` |
| `probe` | Readiness/liveness checks. | `Probe` (SPI), `ProbeContext`, `ProbeResult`, `ProbeRegistry`, `ProbeRunner`, `ProcessProbe`, `TcpProbe`, `HttpProbe`, `LogPatternProbe`, `FileProbe`, `CommandProbe`, `AgentProbe` |
| `agentlink` | Wrapper side of the agent channel (listener, per-connection session), used by `probe` and `lifecycle`. | `AgentListener`, `AgentChannel` |
| `log` | Wrapper logging facade and rolling file output honouring `wrapper.logfile.*`. | `WrapperLog`, `LogLevel`, `LogFormat`, `RollingFileHandler` |
| `platform` | OS boundary. Everything the core needs from the OS, as interfaces. | `Platform` (factory), `ServiceController` (install/remove/start/stop/status), `ServiceHost` (run as service, report status), `ServiceStatus`, `ProcessTools`, `ServiceException` (SCM operations), `PlatformException` (OS-level) |
| `platform.windows` | Windows implementations. | `ScmApi` and `ProcessApi` (thin facades, one method per Win32 call we use), `JnaScmApi`, `JnaProcessApi`, `WindowsServiceController`, `WindowsServiceHost`, `ServiceCommandLine`, `WindowsProcessTools`, `Win32Errors` |
| `platform.windows.jna` | Hand-written JNA bindings for what `jna-platform` lacks (§4.4). | `Advapi32Ext`, `Kernel32Ext`, structures |
| `platform.unsupported` | Console-only fallback for non-Windows hosts; service operations throw `PlatformException`, which `cli` maps to exit code 4. | `UnsupportedServiceController` |
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
  `SetPriorityClass`, `SetProcessAffinityMask`; `Kernel32Util.formatMessage` for error text.
  `Advapi32.INSTANCE` is loaded with `W32APIOptions.DEFAULT_OPTIONS`, so the Unicode entry points
  are used throughout.
* **Hand-written bindings** (`platform.windows.jna`), because `jna-platform` does not provide them:
  the `SERVICE_DELAYED_AUTO_START_INFO` and `SERVICE_PRESHUTDOWN_INFO` structures for
  `ChangeServiceConfig2`; job objects (`CreateJobObject`, `SetInformationJobObject` with
  `JOBOBJECT_EXTENDED_LIMIT_INFORMATION` and `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`,
  `AssignProcessToJobObject`, `TerminateJobObject`); `SetConsoleTitle` and `SetConsoleCtrlHandler`
  (backlog items); LSA functions to grant "Log on as a service" (`LsaOpenPolicy`,
  `LsaAddAccountRights`, `LsaClose`, `LsaNtStatusToWinError`). Every structure gets a layout test
  (`size()`, field order) and is used only through the `ScmApi` / `ProcessApi` facades.
* `ScmApi` (service control) and `ProcessApi` (job objects, `OpenProcess`, console functions) are
  **our** interfaces with one method per Win32 call we use. `JnaScmApi` and `JnaProcessApi`
  implement them and are the only classes that read the last error (`Native.getLastError()`),
  wrapping it into `ServiceException` / `PlatformException` with the Win32 code and message. Tests
  mock the facades; JNA types never leave `platform.windows`. (`W32ServiceManager`/`W32Service` from `jna-platform` are not used: they
  cannot create or delete services, throw their own exception type and are not mockable — D5.)
* **Minimal access rights per command**: `-q` opens the SCM with `SC_MANAGER_CONNECT` and the
  service with `SERVICE_QUERY_STATUS | SERVICE_QUERY_CONFIG`, so it works unelevated; install/remove
  use `SC_MANAGER_CREATE_SERVICE` / `DELETE`; start/stop use `SERVICE_START` /
  `SERVICE_STOP | SERVICE_QUERY_STATUS`.
* **Service registration**: `CreateService(SERVICE_WIN32_OWN_PROCESS, start type, dependencies as a
  double-NUL-terminated list, account, password)`, then `ChangeServiceConfig2` for the description,
  delayed auto-start and the pre-shutdown timeout (`wrapper.shutdown.timeout` plus a margin for the
  final `terminate` step and the `STOPPED` report, never below the Windows default of 180 s). When
  `wrapper.ntservice.account` names a real account (not `LocalSystem`, `LocalService` or
  `NetworkService`), it is granted `SeServiceLogonRight` through the LSA binding (as YAJSW/Tanuki
  do); without it the first start fails with 1069. Binary path, built by
  `ServiceCommandLine` (D12):

  ```
  "<java.home>\bin\javaw.exe" <wrapper.ntservice.java.additional.N …> -Djna.tmpdir=<wrapper.tmp.path> -cp "<java.class.path of the installing JVM>" io.github.jacobosanchez.nyajsw.cli.Main --service "<absolute wrapper.conf>"
  ```

  `java.home` and the default classpath (`java.class.path`) are those of the JVM running `-i`, so the
  command works from an unpacked build and from the distribution alike; callers may pass an explicit
  classpath. `wrapper.java.command` governs only the
  application JVM.
* **Running as the service** (`--service`): `Main` calls `StartServiceCtrlDispatcher` before
  anything else, so the SCM's 30-second start window is never at risk. The table entry name may be
  empty for `SERVICE_WIN32_OWN_PROCESS`; the real name arrives in `ServiceMain`'s `argv[0]`.
  `ServiceMain` (a JNA callback thread, named through `CallbackThreadInitializer`) registers the
  handler, reports `START_PENDING` with `dwControlsAccepted = 0`, loads configuration and logging,
  starts the supervisor, and reports `RUNNING` (accepting `STOP`, `SHUTDOWN`, `PRESHUTDOWN`) once
  readiness passes. It blocks until the supervisor is terminal and reports `STOPPED` last. The
  control handler reports `STOP_PENDING` synchronously, queues the event to the supervisor and
  returns. Callback objects are kept in static fields so the native pointers stay valid (the one
  sanctioned static state, AGENTS.md §4.4). When the application cannot be kept running, the
  service ends with `dwWin32ExitCode = ERROR_SERVICE_SPECIFIC_ERROR` (1066) and the reason in
  `dwServiceSpecificExitCode`.
* **Pending states**: the checkpoint is incremented on every report and the wait hint is at least
  twice the readiness interval with a 2-second floor, so long readiness checks never trip the SCM.
  `PRESHUTDOWN` is accepted by default because plain `SHUTDOWN` only leaves the few seconds of
  `WaitToKillServiceTimeout`.
* **Error mapping** (`Win32Errors`): 5 `ERROR_ACCESS_DENIED` → "run as Administrator";
  1053 `ERROR_SERVICE_REQUEST_TIMEOUT`; 1056 `ERROR_SERVICE_ALREADY_RUNNING`;
  1057 `ERROR_INVALID_SERVICE_ACCOUNT`; 1060 `ERROR_SERVICE_DOES_NOT_EXIST`;
  1062 `ERROR_SERVICE_NOT_ACTIVE`; 1069 `ERROR_SERVICE_LOGON_FAILED` → logon-right hint;
  1072 `ERROR_SERVICE_MARKED_FOR_DELETE`; 1073 `ERROR_SERVICE_EXISTS`; anything else through
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
  then system properties and environment. `include =` and
  `#include.required` are required: a missing file or an unresolved variable in the path → error
  naming file and line. A plain `#include` is optional, as in the Tanuki format (YAJSW treats it as a comment), so
  the same problems only warn; an include cycle is always an error. Encoding: UTF-8,
  BOM skipped; BOM + invalid UTF-8 → error; no BOM and invalid UTF-8 → windows-1252 with a warning;
  UTF-16 (BOM or NUL bytes) → error asking for UTF-8 (D7). Backslashes are **literal** except that
  a line ending in an odd number of `\` continues on the next line (as in YAJSW's Commons
  Configuration parser); blank and comment lines inside a continued value are skipped, as there.
  So `C:\app\` must be written `C:\app\\` or `C:/app/`; a continuation line that looks like a new
  `wrapper.*` entry gets a warning. Doubled backslashes in path-typed keys are normalised by
  `ValueParser`: every `\\` becomes `\`, except a leading `\\` followed by another character, which
  is a literal UNC or `\\?\` prefix (`\\server\share\\` gives `\\server\share\`); four leading
  backslashes are its escaped form. Entries keep file and line (`ConfigLocation`) for messages; problems that do
  not stop loading are `ConfigWarning`s. Several statements above (backslash handling, treatment of
  `include`, encoding default) are unconfirmed against YAJSW itself; see the D7 note and P1.9.
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
  reference (`${util.f("${x}")}`) is caught too. Nested references (`${a${b}}`) resolve only the
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
* **Indexed and dynamic keys** (`KeyCollector`): `prefix.N` collected in numeric order, gaps
  allowed; the same index written twice (`.7`, `.07`) keeps both in file order with a warning; free
  placeholders (`wrapper.app.env.<variable>`) keep file order; a key belongs to the family the
  registry resolves it to, so the exact `wrapper.on_exit.0` is not a `wrapper.on_exit.<code>` member;
  `wrapper.app.env.<KEY>`, `wrapper.on_exit.<code>`, `wrapper.filter.trigger.N` handled by
  `KeySpec` patterns (`KeyPattern` templates: `<n>` matches an index, `<code>` a signed exit code,
  any other `<label>` one or more non-empty segments; an exact name wins over a template, then more
  literal characters win, then numeric placeholders over free ones; the registry rejects two equally
  specific templates that can match the same key, and declarations another one shadows).
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
  values wherever they appear, including copies made by `${...}`, and `ConfigDump` is the only
  sanctioned rendering of values.
* **Typed views**: `WrapperConfig` exposes `JavaConfig`, `ServiceConfig`, `LogConfig`,
  `LifecycleConfig`, `ProbeConfig` records. Nothing outside `config` reads raw strings.
* Relative paths resolve against the configuration file's directory unless the key says otherwise
  (D13).

### 4.6 Lifecycle, restart and stop semantics

State machine in `Supervisor`: `IDLE → STARTING → (readiness) → RUNNING → STOPPING → STOPPED`,
with `RESTART_WAIT` between invocations and `FAILED` when the restart policy gives up.

* **Exit handling**: `wrapper.on_exit.<code>` / `wrapper.on_exit.default` → `RESTART`,
  `SHUTDOWN` (stop application and wrapper/service) or `STOP` (stop the application, keep the
  wrapper alive). Default: `RESTART` for non-zero, `SHUTDOWN` for `0` (verify against YAJSW).
  Exit actions are **not** evaluated while the supervisor is `STOPPING`: an application that exits
  because we asked it to is never restarted.
* **Restart policy**: `wrapper.restart.delay` (s), `wrapper.max_failed_invocations` (default 5),
  `wrapper.successful_invocation_time` (default 300 s). An invocation that exits (or fails
  readiness) before the successful-invocation time counts as failed; the counter resets after a
  successful one. Exceeding the limit → `FAILED` → the service reports `STOPPED` with
  `ERROR_SERVICE_SPECIFIC_ERROR`. Note that SCM recovery (failure actions) ignores a service that
  reports `STOPPED` unless `SERVICE_CONFIG_FAILURE_ACTIONS_FLAG` is set; nyajsw sets that flag
  whenever failure actions are configured (P9) and the migration guide explains the interplay (R6).
* **Startup**: `wrapper.startup.timeout` (default 30 s) bounds readiness. On timeout the invocation
  is treated as failed and the restart policy applies.
* **Stop sequence**: an ordered chain of `StopStrategy` instances from `StopStrategyRegistry`,
  selected by `wrapper.stop.strategy`. The registry and the `terminate` strategy exist from P3;
  P7 registers `agent` and changes the default to `agent,terminate` whenever
  `wrapper.agent.enabled=true` (default recorded in the key registry, never hard-coded elsewhere).
  1. `agent` — send `STOP` over the agent channel; the application JVM runs `System.exit(0)` and
     therefore its shutdown hooks.
  2. `command` — run `wrapper.stop.command` (later also a `wrapper.stop.conf` Java program).
  3. `console` (backlog) — `GenerateConsoleCtrlEvent` on the shared console, with the wrapper
     shielded by `SetConsoleCtrlHandler`.
  4. `terminate` — always last, after `wrapper.shutdown.timeout` (default 30 s) is exhausted:
     kills the whole process tree (`TerminateJobObject` when a job object exists, otherwise
     `ProcessHandle.descendants()` + `destroyForcibly()`), because on Windows `Process.destroy()`
     is already `TerminateProcess` and leaves grandchildren (e.g. a wrapped `.bat`) alive.
  Each strategy gets its share of the timeout; the chain is configurable and extensible.
* **Coupling** (`wrapper.control`): `TIGHT` (default; verify) — wrapper exit stops the application
  and application end (after exit actions) stops the wrapper; `APPLICATION` — wrapper follows the
  application; `WRAPPER` — application follows the wrapper; `LOOSE` — independent. On Windows a
  job object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` enforces `TIGHT`/`WRAPPER` even if the
  wrapper dies abruptly: the job is created before launch, the child is assigned right after start
  (`OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE)`), the job handle stays open for the
  wrapper's lifetime, and the tiny pre-assignment window is accepted and documented (P6.4).
* **Pid files**: `wrapper.pidfile` (wrapper) and `wrapper.java.pidfile` (application), written on
  start, removed on stop, stale files overwritten.
* **Console mode**: Ctrl+C reaches both the wrapper and the child because they share the console.
  The wrapper's shutdown hook moves the supervisor to `STOPPING`, waits up to
  `wrapper.shutdown.timeout` for the child to finish its own shutdown, then runs the remaining
  strategies; the exit code returned is the application's.

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
| `tcp` | A TCP connection to host:port succeeds | `wrapper.readiness.tcp.host` (default `127.0.0.1`), `wrapper.readiness.tcp.port` |
| `http` | An HTTP(S) request returns an expected status | `wrapper.readiness.http.url`, `.status` (default `200-399`), `.timeout` |
| `log` | Captured stdout/stderr (or a file) matches a regex | `wrapper.readiness.log.pattern`, `.file` (optional) |
| `file` | A file exists (and is optionally non-empty) | `wrapper.readiness.file.path` |
| `command` | An external command exits `0` | `wrapper.readiness.command`, `.timeout` |
| `agent` | The in-JVM agent connected and answers `PING` | `wrapper.agent.enabled` (default `true`) |
| `jmx` (backlog) | A JMX attribute matches | `wrapper.readiness.jmx.url`, `.attribute`, `.value` |

* `wrapper.readiness.type` is a comma-separated list; **all** listed probes must pass.
  `wrapper.readiness.interval` (default 1 s) is the polling cadence; `wrapper.startup.timeout` is
  the overall bound. While pending, the service host bumps the SCM checkpoint on every report and
  uses a wait hint of at least twice the interval (§4.4).
* Liveness: `wrapper.liveness.type` (default `agent` when the agent is enabled, mirroring YAJSW's
  default pinging; otherwise none, so only process exit is observed), `wrapper.liveness.interval`
  (default 10 s), `wrapper.liveness.failures` (consecutive failures before acting, default 3),
  `wrapper.liveness.action` (`RESTART` default, or `SHUTDOWN`). `wrapper.ping.interval` /
  `wrapper.ping.timeout` from YAJSW map onto the `agent` liveness probe (D16).
* Agent channel: the wrapper listens on a loopback TCP port chosen at launch; the port is passed to
  the child as `-Dnyajsw.agent.port`, the random token as the environment variable
  `NYAJSW_AGENT_TOKEN` so it never appears in a logged command line. Protocol (`AgentProtocol`,
  defined once in `nyajsw-agent`): `HELLO <token>`, `PING`/`PONG`, `STOP`. Line-based, ASCII,
  tested in-process with both ends. The agent never blocks application startup: `premain` returns
  immediately and works on a daemon thread.

### 4.8 Logging

* `WrapperLog` is a thin facade over `java.util.logging` (decision D6: zero dependencies, full
  control over format and rotation). Levels map to YAJSW names: `NONE FATAL ERROR STATUS INFO DEBUG`.
  The facade (with a console sink) arrives in P2 because the launcher already needs a log sink; the
  rolling file handler and formats follow in P4.
* `wrapper.logfile` with `ROLLNUM` and `YYYYMMDD` tokens; `wrapper.logfile.maxsize` (`K`/`M`/`G`
  suffixes), `wrapper.logfile.maxfiles`, `wrapper.logfile.rollmode` (`NONE`, `SIZE`, `DATE`,
  `SIZE_OR_DATE`; verify YAJSW set), `wrapper.logfile.loglevel`, `wrapper.logfile.format`,
  `wrapper.console.loglevel`, `wrapper.console.format`.
* Format tokens follow the Tanuki/YAJSW letter convention (`L` level, `P` prefix, `T` time,
  `Z` millis, `M` message; verify the exact set) with `wrapper` / `jvm N` prefixes so operators
  can tell wrapper lines from captured application output.
* Application stdout/stderr are captured by `OutputPump` (charset: the child's console code page by
  default, overridable) and logged at `INFO`; the same stream feeds the `log` probe and (backlog)
  `wrapper.filter.trigger.N`. The command line is logged through the masking form of
  `JavaCommandBuilder`, so secrets never reach the log.
* `wrapper.debug=true` raises the wrapper log level to `DEBUG`. In service mode nothing is written to
  the console; logging initialises to the file as early as the configuration allows, with a
  bootstrap fallback (`%TEMP%\nyajsw-bootstrap.log`) for errors before the configuration is read.

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
- [ ] P1.6b Typed `WrapperConfig` records (`JavaConfig`, `ServiceConfig`, `LogConfig`,
      `LifecycleConfig`, `ProbeConfig`) built from a validated `LoadedConfig`, with derived defaults
      and relative paths resolved against the configuration directory (D13). Decide per family
      what a repeated index means (lists keep both; exit codes and filter triggers need one value
      per number, joined through `KeyCollector.Member.numbers()`), and warn when a
      `wrapper.on_exit.<code>` member is 0 written differently (`00`, `-0`), since the exact
      `wrapper.on_exit.0` carries the default.
- [ ] P1.7 `docs/COMPATIBILITY.md` generator + staleness test (`-Dnyajsw.docs.update=true`
      rewrites). The registry is already seeded from §7 (P1.2); this item only generates the
      document from it and retires the hand-written §7 tables.
- [ ] P1.8 Fixtures: a YAJSW-style default template written from scratch, plus the owner's
      anonymised real configurations as soon as they are provided (§10 Q1; not a blocker).
- [ ] P1.9 D7 follow-ups once real fixtures exist: honour `wrapper.conf.encoding`; settle backslash
      escaping, `include` handling and case of environment names against a real file; check how
      YAJSW treats an index written twice (`.7` and `.07`; nyajsw keeps both with a warning, P1.4)
      and a key set twice (nyajsw: last value wins with a warning, P1.5; YAJSW's Commons
      Configuration probably keeps a list whose first value scalar getters return, i.e. first wins);
      note that nyajsw's constant defaults are visible to `${...}`, which YAJSW's in-code defaults
      probably are not; confirm the accepted boolean spellings (P1.6a accepts `yes/no/on/off`) and
      the doubled-backslash rule for paths, and whether numbers may be written in hex (`0x10`,
      rejected today); update §4.5.
Done when: every fixture present in the repository loads; every §7 key has a registry entry and a
test.

### P2 — Application launcher
Goal: build and run the application command line exactly as the configuration says.
- [ ] P2.1 `WrapperLog` facade with `LogLevel` and a console sink (file handler comes in P4).
- [ ] P2.2 `JavaCommandBuilder`: `wrapper.java.command` resolution (default: `java`/`javaw` of the
      wrapper's `java.home`; `wrapper.java.command.javaw`), `-Xms/-Xmx` from
      `wrapper.java.initmemory/maxmemory`, `wrapper.java.additional.N`, `-Djava.library.path` from
      `wrapper.java.library.path.N`, main class vs `wrapper.java.app.jar`, `wrapper.app.parameter.N`;
      quoting policy for Windows (`ProcessBuilder` re-quotes arguments and rejects embedded quotes
      unless `jdk.lang.Process.allowAmbiguousCommands` is set — decide, document, test values such
      as `-Dx="a b"`); golden tests compare argument arrays, not joined strings; masking loggable form.
- [ ] P2.3 `ClasspathExpander`: `wrapper.java.classpath.N` with `*`/`**` wildcards, directories,
      relative to the working dir, deterministic order, de-duplication.
- [ ] P2.4 `ApplicationProcess` + `OutputPump`: `ProcessBuilder` with `wrapper.app.env.*`,
      `wrapper.working.dir`, `wrapper.tmp.path`, piped output with explicit charset, pid, exit code,
      destroy / process-tree termination helper.
- [ ] P2.5 Console mode `-c` for a single run (no restart loop yet); Ctrl+C waits up to
      `wrapper.shutdown.timeout` for the child, then terminates it (full semantics in P3.6).
- [ ] P2.6 (optional) `wrapper.image` for non-Java executables through the same `ApplicationProcess`.
Done when: golden command-line tests pass on fixtures; a test main class is launched and its output
captured on Linux and Windows.

### P3 — Supervisor, probe SPI and stop strategies
Goal: restart policy, timeouts and stop sequence, fully unit-tested with fake clock/process.
- [ ] P3.1 `Supervisor` state machine and `State` transitions (table-driven tests), including
      "no exit actions while `STOPPING`".
- [ ] P3.2 `ExitAction`/`ExitActionResolver` and `RestartPolicy` (`restart.delay`,
      `max_failed_invocations`, `successful_invocation_time`), `wrapper.control`.
- [ ] P3.3 `Probe` SPI, `ProbeContext`/`ProbeResult`, `ProbeRegistry`, `ProbeRunner` and
      `ProcessProbe`; `wrapper.startup.timeout` bounds readiness.
- [ ] P3.4 `StopStrategy` SPI, `StopStrategyRegistry`, `TerminateStopStrategy` (process tree),
      `wrapper.stop.strategy` (default `terminate` for now), `wrapper.shutdown.timeout`.
- [ ] P3.5 `PidFiles` (`wrapper.pidfile`, `wrapper.java.pidfile`).
- [ ] P3.6 Console mode runs through the supervisor (restart loop, Ctrl+C semantics, exit code).
Done when: all transitions, timeouts and counters are covered by deterministic tests.

### P4 — Logging
Goal: `wrapper.logfile.*` / `wrapper.console.*` behaviour.
- [ ] P4.1 YAJSW level names mapping, `wrapper.debug` / `wrapper.java.debug`, bootstrap log for
      pre-configuration failures.
- [ ] P4.2 `LogFormat` tokens for console and file formats.
- [ ] P4.3 `RollingFileHandler`: size and date rolling, `ROLLNUM`/`YYYYMMDD`, `maxsize`, `maxfiles`,
      Windows rename retries.
- [ ] P4.4 Application output logged with `jvm N` prefix.
Done when: rotation and formatting are tested against temp directories; no third-party logging dep.

### P5 — Windows service control (client side)
Goal: `-i -r -t -p -q` against the real SCM through JNA.
- [ ] P5.1 `Platform` detection/factory; `platform.unsupported` fallback (`PlatformException` →
      exit code 4 in `cli`).
- [ ] P5.2 `ScmApi` facade + `JnaScmApi` (adds `jna`/`jna-platform` to `nyajsw-wrapper`; last-error handling in one place); hand-written
      `SERVICE_DELAYED_AUTO_START_INFO` / `SERVICE_PRESHUTDOWN_INFO` with layout tests;
      `WindowsServiceController` (install with start type, dependencies as `\0`-separated list with
      a test, account, description, delayed start, pre-shutdown timeout with margin; remove; start; stop; status
      with pid), minimal access rights per command, wait-for-state helper, `Win32Errors` mapping.
- [ ] P5.3 `ServiceCommandLine`: binary path from `java.home`, an explicit classpath parameter
      (default `java.class.path`; integration tests pass the real jars because surefire's
      `java.class.path` is a manifest-only booter jar), `wrapper.ntservice.java.additional.N`,
      `-Djna.tmpdir`, quoting (D12).
- [ ] P5.4 CLI commands `-i -r -t -p -q` with human-readable output and exit codes.
- [ ] P5.4a (blocked on §10 Q8, D17) Migration entry point: `WrapperServiceBooter` alias class, mode
      detection when `--service` is absent, and the Java 21 binPath rewrite in a migration command.
- [ ] P5.5 Grant `SeServiceLogonRight` at install when `wrapper.ntservice.account` names a real
      account (skipped for the built-in `LocalSystem`/`LocalService`/`NetworkService`); hand-written
      LSA binding; clear error when it cannot be granted.
- [ ] P5.6 `windows-integration` tests: install → query → remove a throwaway service (start/stop
      come with P6), cleanup in `@AfterMethod(alwaysRun = true)`.
Done when: unit tests cover every `ScmApi` interaction with mocks; integration job green on Windows.

### P6 — Service host (running as the service)
Goal: the wrapper itself runs under the SCM.
- [ ] P6.1 `ServiceHost` interface + `FakeServiceHost` for tests; `WindowsServiceHost` with
      `StartServiceCtrlDispatcher`, `RegisterServiceCtrlHandlerEx`, `SetServiceStatus`,
      checkpoint/wait-hint reporting, `STOP`, `SHUTDOWN`, `PRESHUTDOWN` (accepted by default),
      `INTERROGATE`; named callback thread; `STOPPED` reported last.
- [ ] P6.2 `--service <conf>` command: dispatcher first, then configuration, file-only logging,
      supervisor wiring with `START_PENDING` until `ProbeRunner` readiness (`process` probe),
      `ERROR_SERVICE_SPECIFIC_ERROR` on `FAILED`.
- [ ] P6.3 Service-account hardening: working directory default, clear errors for unwritable
      log/pid paths, documentation of `jna.tmpdir` / `jnidispatch.dll` options.
- [ ] P6.4 Job object (hand-written binding) with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` for
      `TIGHT`/`WRAPPER`; `TerminateJobObject` used by the `terminate` strategy when present.
- [ ] P6.5 `windows-integration` end-to-end: install → start → `RUNNING` → stop → `STOPPED` →
      remove, with a test application and the wrapper log attached on failure.
Done when: a real service runs a test application from an unmodified YAJSW-style `wrapper.conf`.

### P7 — Probes and agent
Goal: configurable readiness/liveness; graceful stop through the agent.
- [ ] P7.1 `wrapper.readiness.*` / `wrapper.liveness.*` configuration (probe selection, intervals,
      failure counts) and liveness failures → action. The `START_PENDING`-until-ready flow already
      exists from P6 with the `process` probe.
- [ ] P7.2 `TcpProbe`, `HttpProbe`, `LogPatternProbe`, `FileProbe`, `CommandProbe` (tests with
      local `ServerSocket`/`HttpServer`, temp files, fake clock).
- [ ] P7.3 `nyajsw-agent`: `premain`, `AgentProtocol`, loopback connection with token from
      `NYAJSW_AGENT_TOKEN`, `STOP` → `System.exit(0)`; wrapper side `agentlink` (`AgentListener`,
      `AgentChannel`), `AgentProbe`, `AgentStopStrategy`; automatic `-javaagent` injection unless
      `wrapper.agent.enabled=false`; default stop strategy becomes `agent,terminate` and default
      liveness `agent` when enabled; `wrapper.ping.*` mapping; round-trip test with both ends.
- [ ] P7.4 `docs/probes.md` user guide.
Done when: each probe has unit tests; an end-to-end Windows test reaches `RUNNING` only after an
HTTP probe passes and stops gracefully through the agent.

### P8 — Packaging, migration, release
- [ ] P8.1 `nyajsw-dist`: shaded `nyajsw.jar` (no relocation of `com.sun.jna`, native resources
      kept), `nyajsw-agent.jar`, `bin/*.bat` mirroring YAJSW script names, `conf/wrapper.conf`
      fully commented template, zip assembly.
- [ ] P8.2 `docs/migration-from-yajsw.md`: what changes, how to read the warnings, differences (§3.4),
      SCM recovery interplay (R6).
- [ ] P8.3 Run the owner's real configuration(s) end to end on Windows; close gaps; matrix updated.
- [ ] P8.4 *(recommended)* Release process: version tag, CI builds and attaches the zip to a GitHub
      release.
- [ ] P8.5 Complete `README.md`.
Done when: a YAJSW deployment is switched to nyajsw with no configuration edits (warnings only).

### P9 — Backlog (not scheduled; pick up only when asked)
- JVM discovery keys (`wrapper.java.command.minVersion/maxVersion/jreOnly/jdkOnly/preferJre/preferJdk`).
- `wrapper.java.customProcName`; `wrapper.java.initmemory.relative` / `maxmemory.relative`;
  `wrapper.priority`, `wrapper.affinity`; `wrapper.console.title` (`SetConsoleTitle` binding),
  `wrapper.console.visible`.
- `wrapper.filter.trigger.N` + nyajsw `wrapper.filter.action.N` (`RESTART`/`SHUTDOWN`).
- `wrapper.stop.conf` (stop program) as a `command` stop strategy; `console` stop strategy via
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
- P1.2: `KeyPattern.commonExample` only finds overlaps with one segment per placeholder; free
  placeholders that span several segments in different shapes can still overlap undetected. No
  declared key is affected; revisit if a template with two free placeholders is added.
- P1.6a: `SecretMasker` hides secrets by matching their text, so a short or common secret also masks
  unrelated text and the mask's position can hint at it (password `Java` shows as `C:\****\bin`).
  Tracking during interpolation which values were built from secret keys (or from names a secret
  referred to) would avoid both; do it if real configurations show the problem.
- P1.4: `wrapper.app.env.PATH` and `wrapper.app.env.Path` are collected as two variables without a
  warning, though Windows treats them as one; warn in P1.6b or P2.4 when building the environment.
- P1.3: no bound on interpolation depth or expanded size; a very long reference chain overflows the
  stack and doubling chains grow exponentially. Low risk (files are written by administrators); add
  limits if real configurations show the need.
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

## 7. Configuration compatibility matrix (initial, hand-written — superseded by the generated `docs/COMPATIBILITY.md` after P1.7)

Status: **S** supported · **P** planned (phase) · **I** ignored with warning · **U** unsupported with
warning · **F** unsupported, fatal when present. **X** marks a nyajsw extension (origin `NYAJSW`) and
is combined with a status, e.g. `X P7`. "(verify)" marks a
YAJSW key name or default that must be checked against the YAJSW reference before implementation.

### Java process
| Key | Meaning | Status |
|---|---|---|
| `wrapper.java.command` | Java executable for the application (default: wrapper's JVM) | P2 |
| `wrapper.java.command.javaw` | Use `javaw.exe` | P2 |
| `wrapper.java.command.minVersion/.maxVersion/.jreOnly/.jdkOnly/.preferJre/.preferJdk` | JVM discovery | U → P9 |
| `wrapper.java.customProcName` | Renamed copy of `java.exe` | U → P9 |
| `wrapper.java.app.mainclass` | Application main class | P2 |
| `wrapper.java.app.jar` | Executable jar (alternative to main class) | P2 |
| `wrapper.java.mainclass` | YAJSW in-JVM bootstrap class | I (agent used instead) |
| `wrapper.app.parameter.N` | Program arguments | P2 |
| `wrapper.java.classpath.N` | Classpath entries, wildcards | P2 |
| `wrapper.java.library.path.N` | `java.library.path` entries | P2 |
| `wrapper.java.additional.N` | JVM options and `-D` system properties | P2 |
| `wrapper.java.initmemory` / `wrapper.java.maxmemory` | `-Xms` / `-Xmx` in MB | P2 |
| `wrapper.java.initmemory.relative` / `wrapper.java.maxmemory.relative` | Percent of physical RAM | U → P9 |
| `wrapper.app.env.<KEY>` | Environment variable for the application | P2 |
| `wrapper.working.dir` | Working directory (default: configuration directory, D13) | P2 |
| `wrapper.tmp.path` | Temporary directory (also `jna.tmpdir`) | P2 |
| `wrapper.image` | Non-Java executable to wrap | P2 (optional) |
| `wrapper.priority` / `wrapper.affinity` | Process priority / CPU affinity | U → P9 |
| `wrapper.app.account` / `wrapper.app.password` | Run application as another user | F → P9 |
| `wrapper.java.monitor.*` (verify) | Deadlock/heap/GC monitoring | U → P9 |
| `wrapper.groovy` | Groovy scripting | U |

### Lifecycle
| Key | Meaning | Status |
|---|---|---|
| `wrapper.control` | `TIGHT`/`LOOSE`/`APPLICATION`/`WRAPPER` coupling (default verify) | P3 |
| `wrapper.on_exit.<code>` / `wrapper.on_exit.default` | `RESTART`/`SHUTDOWN`/`STOP` | P3 |
| `wrapper.restart.delay` | Seconds between invocations | P3 |
| `wrapper.max_failed_invocations` | Give up after N failed starts (default 5) | P3 |
| `wrapper.successful_invocation_time` | Seconds after which an invocation counts as successful (default 300) | P3 |
| `wrapper.startup.timeout` | Readiness bound in seconds (default 30) | P3 |
| `wrapper.shutdown.timeout` | Graceful stop bound in seconds (default 30) | P3 |
| `wrapper.pidfile` / `wrapper.java.pidfile` | Pid files | P3 |
| `wrapper.ping.interval` (verify) / `wrapper.ping.timeout` | Heartbeat via agent | P7 |
| `wrapper.restart.reload_configuration` (verify) | Reload file on restart | U → P9 |
| `wrapper.stop.conf` | Separate stop program | U → P9 |
| `wrapper.exit_on_main_terminate` / `wrapper.exit_on_main_exception` (verify) | In-JVM behaviour | U |
| `wrapper.filter.trigger.N` | Log pattern trigger | U → P9 |
| `wrapper.filter.script.N` / `.args` | Groovy on trigger | U |
| `wrapper.stop.strategy`, `wrapper.stop.command` | Ordered stop strategies (§4.6) | X P3/P7 |

### Logging
| Key | Meaning | Status |
|---|---|---|
| `wrapper.logfile` | Log path with `ROLLNUM`/`YYYYMMDD` | P4 |
| `wrapper.logfile.format` / `.loglevel` / `.maxsize` / `.maxfiles` / `.rollmode` | File log settings | P4 |
| `wrapper.console.format` / `.loglevel` | Console log settings | P4 |
| `wrapper.console.visible` / `.title` | Console window | U → P9 |
| `wrapper.console.pipestreams` (verify) | Output piping mode | U |
| `wrapper.debug` / `wrapper.java.debug` | Verbose logging | P4 |
| `wrapper.syslog.*` (verify) | Syslog | U |

### Windows service
| Key | Meaning | Status |
|---|---|---|
| `wrapper.ntservice.name` | Service name (default verify: main class simple name) | P5 |
| `wrapper.ntservice.displayname` / `.description` | Display name / description | P5 |
| `wrapper.ntservice.starttype` | `AUTO_START` (default), `DEMAND_START`, `DELAYED_AUTO_START` (alias `DELAY_START`), `DISABLED` | P5 |
| `wrapper.ntservice.dependency.N` | Service dependencies | P5 |
| `wrapper.ntservice.account` / `.password` | Service account (logon right granted at install) | P5 |
| `wrapper.ntservice.interactive` | Interactive desktop (obsolete on modern Windows) | I |
| `wrapper.ntservice.failure_actions.*` (verify) | SCM recovery | U → P9 |
| `wrapper.ntservice.java.additional.N` | Options for the **wrapper** JVM in the service binary path | X P5 |

### Out of scope (accepted, warned, no effect)
| Key | Status |
|---|---|
| `wrapper.daemon.dir` / `.pid.dir` / `.run_level_dir`, `wrapper.update_rc` | U (POSIX, P9) |
| `wrapper.tray`, `wrapper.tray.port`, `wrapper.tray.icon` | U |
| `wrapper.jmx`, `wrapper.jmx.rmi.port` / `.user` / `.password` | U |

### nyajsw extensions
| Key | Meaning | Status |
|---|---|---|
| `wrapper.readiness.type`, `wrapper.readiness.interval`, `wrapper.readiness.<type>.*` | Readiness probes (§4.7) | X P7 |
| `wrapper.liveness.type` / `.interval` / `.failures` / `.action` | Liveness probes | X P7 |
| `wrapper.agent.enabled` | Inject the agent (default `true`) | X P7 |
| `wrapper.filter.action.N` | Action for `wrapper.filter.trigger.N` | X P9 |

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
| R11 | Unverified YAJSW defaults/key names (marked "(verify)") | Confirm against the YAJSW reference (read only in summarised form so far) and the owner's real files/`doc` folder before implementing each key; record the outcome in the registry. |
| R12 | Windows argument quoting (`ProcessBuilder` re-quoting, embedded quotes) | Explicit policy and tests in P2.2; golden tests on argument arrays. |

## 9. Decisions log (ADR-lite)

| ID | Decision | Rationale | Status |
|---|---|---|---|
| D1 | Clean-room reimplementation, Apache-2.0; compatibility only at the configuration/CLI surface | YAJSW is LGPL and unmaintained; avoid license entanglement and legacy design | Accepted |
| D2 | Wrapper requires Java 21; agent compiled to Java 8 bytecode with zero dependencies (tests on 21) | Wrapped applications may run old JVMs; agent is injected into them | Accepted |
| D3 | Maven multi-module: `nyajsw-agent`, `nyajsw-wrapper`, `nyajsw-dist`; wrapper depends on agent for `AgentProtocol`, never the reverse | Separate compilation target for the agent; one definition of the protocol | Accepted (P0.4 build plugins, parent POM properties: clean 3.5.0, resources 3.5.0, compiler 3.16.0, surefire 3.5.6, jar 3.5.1, install 3.2.0, deploy 3.2.0, enforcer 3.7.0, dependency 3.11.0) |
| D4 | TestNG + Mockito (inline mock maker via `-javaagent` in surefire); Windows-only tests in group/profile `windows-integration` | Owner requirement; keep unit suite portable; avoid JDK 21 dynamic-agent warnings. Surefire stays on the 3.5.x line with an explicit `surefire-testng` provider: 3.6.x ships no `surefire-testng` and runs TestNG only through the JUnit Platform `testng-engine` bridge, which does not support `testng.xml` suites and turns TestNG's default reporters (`testng-results.xml`) off unless configured | Accepted (P0.4: TestNG 7.12.0, Mockito 5.24.0, surefire + surefire-testng 3.5.6) |
| D5 | JNA + `jna-platform` for SCM calls; our own `ScmApi` facade; hand-written bindings in `platform.windows.jna` for delayed-auto-start/pre-shutdown structures, job objects, console functions and LSA; `W32ServiceManager`/`W32Service` not used (no create/delete, own exception type, not mockable) | Reuse maintained generic bindings where they exist; mockable boundary; known gaps listed up front | Accepted (P0.4: JNA and `jna-platform` 5.19.1, managed in the parent POM; added to `nyajsw-wrapper` in P5.2) |
| D6 | Logging on `java.util.logging` with a custom rolling handler; no SLF4J/Logback | Zero deps; exact control over `wrapper.logfile.*` semantics | Proposed (confirm in P4) |
| D7 | Custom line parser (§4.5); literal backslashes except odd-trailing-`\` continuation, skipping blank/comment lines inside it; UTF-8 with BOM skipped, windows-1252 fallback with a warning, UTF-16 rejected; `#include` (optional: missing file warns), `#include.required` and case-insensitive `include =` (required); `${sys:}`/`${env:}` prefixes. Deliberate deviations from Commons Configuration: only `=` separates key and value (no `:`/whitespace separator); no comma splitting of values, `include` included; nested includes resolve against the including file first; a value continued at end of file is kept with a warning instead of dropped; UTF-8 files are read as UTF-8 (Commons Configuration defaults to ISO-8859-1; YAJSW's setting unverified, see Q1), so non-ASCII text in UTF-8 files may now read differently | Windows paths dominate; match what YAJSW's Commons Configuration parser accepted; avoid `java.util.Properties` escape surprises; never silently drop input | Proposed (P1.1 implemented without real files; confirm with §10 Q1). Evidence from YAJSW's public parameter page (2026-11-06) that differs from this decision, to be resolved in P1.2/P1.3 with real fixtures: it names `=` as the separator, `,` as a list delimiter (escaped `\\,`) while advising against lists, backslash as an escape character with `/` preferred in paths, `include=` as an ordinary property whose value may contain `${...}`, environment names lower-cased in `${...}`, and encodings set by `wrapper.conf.encoding`, defaulting to the platform charset. Follow-ups: honour `wrapper.conf.encoding` and verify the backslash and lower-cased-environment behaviour against a real file; `${...}` in include paths is supported since P1.3 |
| D8 | Single key registry drives parsing, validation, warnings and generated docs. Keys are `public static final KeySpec` constants in `ConfigKeys`, collected into one immutable `KeyRegistry`; derived defaults are declared there by description and computed by the typed view that owns the key (P1.6), because they depend on typed values (e.g. `wrapper.agent.enabled`) or the configuration file's location rather than on raw strings | One source of truth; no drift between code and docs; constants let code refer to keys without repeating names | Accepted (P1.2) |
| D9 | Extensions live under `wrapper.*`, tagged `NYAJSW` in the registry | One namespace for users; provenance still visible | Accepted |
| D10 | Agent enabled by default; loopback + random token passed through the environment, port through `-D` | Graceful stop and heartbeat need it; YAJSW also injected code by default; token never on a logged command line | Proposed (owner to confirm, §10 Q3) |
| D11 | groupId and base package `io.github.jacobosanchez.nyajsw` | Matches the GitHub owner; Maven Central-compatible | Proposed (§10 Q2) |
| D12 | Service binary path = installing JVM's `javaw.exe` + classpath (default `java.class.path`) + `wrapper.ntservice.java.additional.N` + `-Djna.tmpdir` + `--service <conf>`; `wrapper.java.command` only affects the application JVM | Wrapper needs Java 21 and JNA settings before configuration is read; works from unpacked builds and the distribution | Proposed |
| D13 | Relative paths and default working directory resolve against the configuration file's directory | Services start in `System32`; configuration-relative is predictable | Proposed (verify YAJSW) |
| D14 | Start type default `AUTO_START`; enum aliases accepted | Match YAJSW defaults and spellings seen in the wild | Accepted |
| D15 | Probe SPI, probe registry and stop-strategy registry are introduced in P3 with `process`/`terminate`; later probes and strategies are additions | Avoid rewriting the supervisor when P7 lands | Accepted |
| D16 | Default liveness probe is `agent` when the agent is enabled (`wrapper.ping.*` semantics), none otherwise | YAJSW pings by default; keep behaviour parity without forcing the agent | Proposed |
| D17 | Migration path (design only, blocked on §10 Q8): ship a thin alias class named like YAJSW's service booter (`org.rzo.yajsw.boot.WrapperServiceBooter`, a deliberate exception to D11 because existing service registrations store that name) that detects service mode when `--service` is absent and delegates to the normal entry point; a migration command rewrites an existing service's binary path to a Java 21 `javaw.exe` and our classpath (D12), since a bare jar swap cannot work when the old registration names an older JVM; `-D` options on the wrapper command line (e.g. `-Dwrapper.tmp.path`, a `jna_tmpdir` property, `-Dpath`) feed the system-property layer of §4.6; we also set the real `jna.tmpdir` from `wrapper.tmp.path` (R3). Short CLI options already exist in §3 | Observed in a vendor's public knowledge base (not tested by us): its services are registered as `java.exe … WrapperServiceBooter <conf>`, installed with `-i <conf>`, and re-pointed with `sc config` plus `-D` flags for non-admin accounts. Whether the underscore-spelled `jna_tmpdir` is read by YAJSW or JNA is unknown | Proposed (P5.4a; confirm with §10 Q1 and Q8) |

## 10. Open questions for the project owner

1. **Real configuration files.** Please add the current `wrapper.conf` (anonymised) as a fixture,
   together with the `conf/wrapper.conf.default` template and `doc/` folder of the YAJSW version in
   use (reading key names and defaults is within the clean-room rule), and tell us the Java version
   the application runs on. This settles D7 and the "(verify)" entries. In particular: does any
   file rely on `:` or whitespace as the key/value separator, on comma-separated lists in values
   (Commons Configuration's list delimiter, unless YAJSW disables it), or on `${...}` in include
   paths? What encoding are the files saved in? Anonymised copies of your own installed
   `service.conf` files are the fixtures we need most. Do not add vendor templates unless their
   licence allows redistribution in an Apache-2.0 repository.
2. **Package/groupId** `io.github.jacobosanchez.nyajsw` acceptable? (D11)
3. **Agent injection by default** acceptable for your application? It adds one `-javaagent` option
   to the child JVM. (D10)
4. **POSIX** needed in the foreseeable future? It changes nothing now but affects P9 priority.
5. **Windows versions and service accounts** used in production (LocalSystem, a domain account,
   LocalService)? Affects P5.5/P6.3 hardening and the test matrix.
6. **Windows Event Log** reporting wanted (backlog item)?
7. **CI and releases on GitHub Actions** wanted (P0.3, P8.4)? They are recommended, not requested.
8. **Takeover of existing services.** Should nyajsw be able to run under services that YAJSW already
   registered (same `WrapperServiceBooter` class name and `-i`/`-r` options), so that swapping the
   jar is enough, or is a reinstall with our own installer acceptable? Which service names, product
   version and run-as accounts are in use? (D17)

## 11. References

* YAJSW configuration reference: https://yajsw.sourceforge.io/YAJSW%20Configuration%20Parameters.html
  (key names in §7 come from YAJSW's published default template as reproduced in third-party
  examples, hence the "(verify)" marks). The page was read in summarised form on 2026-11-06 and
  informed the D7 note; a full read of the key reference is pending.
* Microsoft Learn — Service functions: https://learn.microsoft.com/windows/win32/services/service-functions
* JNA: https://github.com/java-native-access/jna (`com.sun.jna.platform.win32.Advapi32`, `Winsvc`, `Kernel32`)
* TestNG: https://testng.org/
