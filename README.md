# nyajsw

**nyajsw** ("new YAJSW") is a clean-room Java 21 service wrapper that runs a Java application as a
Windows service. It replaces [YAJSW](https://yajsw.sourceforge.io/) (Yet Another Java Service
Wrapper) for deployments that want to move off it. It reads existing YAJSW `wrapper.conf` files
without edits, and warns about anything it does not support. On top of that it adds readiness and
liveness probes, so "the service started" can mean "the application answers", not just "a process
exists".

> **Status: not released yet.** Configuration, console mode, the Windows service, logging,
> probes and the agent work and are tested, including end to end against the real Windows
> Service Control Manager. Packaging is done. A release, and checks against real deployments,
> are still to come; see the [implementation plan](docs/IMPLEMENTATION_PLAN.md).

## Contents

- [What it does](#what-it-does)
- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Quick start](#quick-start)
- [A first configuration](#a-first-configuration)
- [Command line](#command-line)
- [The configuration file](#the-configuration-file)
- [Running the application](#running-the-application)
- [Restarts and the application's exit](#restarts-and-the-applications-exit)
- [Stopping the application](#stopping-the-application)
- [Readiness and liveness](#readiness-and-liveness)
- [Logging](#logging)
- [The Windows service](#the-windows-service)
- [Security](#security)
- [Troubleshooting](#troubleshooting)
- [Differences from YAJSW](#differences-from-yajsw)
- [Documentation](#documentation)
- [Building](#building)
- [Modules](#modules)
- [Contributing](#contributing)
- [License](#license)

## What it does

- Runs the application in its own JVM, which may be any Java 8 or later
  (`wrapper.java.command`), and restarts it according to `wrapper.on_exit.*` and the restart keys.
- Installs, starts, stops, queries and removes the Windows service with YAJSW's switches
  (`-i -t -p -q -r`), or runs the application in the console (`-c`).
- Reports the service as running only once readiness probes pass. The probes are `process`,
  `tcp`, `http`, `log`, `file`, `command` and `agent`; liveness probes restart a hung application.
- Stops the application gracefully: an agent in the application's JVM (on by default) ends it
  with `System.exit(0)`, so its shutdown hooks run. Without the agent, or when the application is
  still running after the stop budget, its whole process tree is terminated.
- Leaves nothing running: under the default `wrapper.control=TIGHT`, the processes the application
  starts run in a Windows job object and end with the wrapper's supervision (see
  [Windows service](docs/windows-service.md#processes-the-application-starts) for the exceptions).
- Writes the wrapper log with the application's output, rolled by size, by date or at each start,
  as `wrapper.logfile.*` says. Passwords are masked in log lines and messages.
- Explains itself: every `wrapper.*` key it does not use gets a warning that names the file and
  line, and Windows errors come with their number and text, plus "run as Administrator" where that
  is the cause.

## How it works

```
 Windows Service Control Manager (services.exe)
     │  start / stop / query                                   (advapi32 through JNA)
     ▼
 ┌──────────────────────────────────────────────────────────────────────┐
 │ nyajsw wrapper JVM (Java 21)                                         │
 │   reads wrapper.conf, reports the service's state to Windows,        │
 │   supervises the application: probes, restarts, stop, log            │
 └───────────────────────────────┬──────────────────────────────────────┘
                                 │ starts it with its own command line, working
                                 │ directory and environment; captures its output
                                 ▼
 ┌──────────────────────────────────────────────────────────────────────┐
 │ application JVM (Java 8 or later, chosen by wrapper.java.command)    │
 │   the application's main class or jar, its JVM options               │
 │   [optional] nyajsw-agent.jar (-javaagent): heartbeat, graceful stop │
 └──────────────────────────────────────────────────────────────────────┘
```

- **Two JVMs.** The wrapper always runs on Java 21 or later. The application runs in a separate
  JVM that the configuration chooses, so an application that needs Java 8 keeps it.
- **The agent** is a small jar with no dependencies that the wrapper loads into the application's
  JVM. It connects back to the wrapper over `127.0.0.1` with a random token, answers heartbeat
  pings and ends the application on request. The application needs no code changes for it, and it
  can be turned off with `wrapper.agent.enabled=false`.
- **Console mode** (`-c`) is the same wrapper without the Windows service around it: it runs in the
  foreground, and Ctrl+C stops the application through the same stop sequence.
- **Windows integration** uses the generic Service Control Manager functions of `advapi32`, called
  through [JNA](https://github.com/java-native-access/jna). There is no native installer: `-i`
  registers the service and, for a named account, grants it the right to log on as a service, and
  JNA unpacks its native library into a temporary directory when the wrapper starts.

## Requirements

| For | Needs |
|---|---|
| The wrapper | Windows, and Java 21 or later |
| The application | Java 8 or later; it may be the wrapper's own Java or another one |
| Installing, starting, stopping or removing the service | An Administrator prompt (`-q` and `-c` need none) |
| Building from source | JDK 21 or later and Maven 3.9 or later (see [Building](#building)) |

## Quick start

1. Build the distribution (see [Building](#building)) and unpack `nyajsw-<version>.zip`,
   preferably under `C:\Program Files` (see [Security](#security)):

   ```
   nyajsw-<version>\
     nyajsw.jar, nyajsw-agent.jar
     bin\        nyajsw.bat, runConsole.bat, installService.bat, startService.bat,
                 stopService.bat, queryService.bat, uninstallService.bat
     conf\       wrapper.conf (a commented template)
     docs\       the guides below
     LICENSE, README.md, THIRD-PARTY.txt
   ```

2. Edit `conf\wrapper.conf`, or use an existing YAJSW `wrapper.conf`. The named scripts take the
   configuration file as an optional argument, by default the installation's `conf\wrapper.conf`.
   To move a service that YAJSW runs today, follow
   [Moving from YAJSW](docs/migration-from-yajsw.md) instead of steps 3 to 5: its service must be
   removed first.
3. Check the configuration. This changes nothing and needs no Administrator rights: it reads the
   file as the service will, prints a warning for every key nyajsw does not use, and shows the
   state of the service of that name:

   ```
   bin\queryService.bat
   ```

4. Try the application in the console; Ctrl+C stops it. This also shows the problems that only
   come up when the application is started, which `-q` cannot see: an unknown probe type, a
   `wrapper.java.command` that does not exist. It runs under your account and environment, not the
   service's:

   ```
   bin\runConsole.bat
   ```

5. From an Administrator prompt, install and start the service:

   ```
   bin\installService.bat
   bin\startService.bat
   ```

   The service shows as *starting* until the application is ready, then as *running*. The wrapper
   log, `wrapper.log` beside the configuration file unless `wrapper.logfile` says otherwise, shows
   what happened.

The scripts run the wrapper on the Java that `NYAJSW_JAVA` names (a `java.exe`), else the one in
`JAVA_HOME`, else `java` from the `PATH`. The service is registered with that Java's `javaw.exe`,
unless `wrapper.ntservice.java.command` names another Java 21 or later.

## A first configuration

The template `conf\wrapper.conf` lists the common keys with comments. A small but complete
configuration for a Spring Boot style jar on Java 17, which counts as ready once its health URL
answers:

```properties
# The application
wrapper.java.command=C:/Program Files/Java/jdk-17/bin/java.exe
wrapper.working.dir=..
wrapper.java.app.jar=lib/app.jar
wrapper.java.additional.1=-Dfile.encoding=UTF-8
wrapper.java.additional.2=-Dspring.profiles.active=production
wrapper.java.initmemory=256
wrapper.java.maxmemory=1024
wrapper.app.parameter.1=--server.port=8080

# Restart it when it fails, give up after 5 quick failures in a row
wrapper.on_exit.default=RESTART
wrapper.on_exit.0=SHUTDOWN

# Running means: the health URL answers
wrapper.readiness.type=http
wrapper.readiness.http.url=http://127.0.0.1:8080/actuator/health
wrapper.startup.timeout=120

# Logging
wrapper.logfile=../log/wrapper.log
wrapper.logfile.maxsize=10M
wrapper.logfile.maxfiles=10

# The Windows service
wrapper.ntservice.name=example-app
wrapper.ntservice.displayname=Example application
wrapper.ntservice.description=Runs the example application under nyajsw.
wrapper.ntservice.starttype=DELAYED_AUTO_START
```

With the file in the installation's `conf` folder, `wrapper.working.dir=..` makes the installation
directory the application's working directory, so `lib/app.jar` is found there. `../log/wrapper.log`,
like every path of the wrapper's own, is relative to the configuration file's folder, so it also
lands in the installation's `log` folder.

## Command line

`nyajsw` below stands for `bin\nyajsw.bat`, or `java -jar nyajsw.jar` run with Java 21 or later.

| Switch | Long form | Script | What it does |
|---|---|---|---|
| `-c <conf>` | `--console` | `runConsole.bat` | Runs the application in the console until it ends or Ctrl+C |
| `-i <conf>` | `--install` | `installService.bat` | Installs the service named by `wrapper.ntservice.name` |
| `-t <conf>` | `--start` | `startService.bat` | Starts the service and waits until it runs |
| `-p <conf>` | `--stop` | `stopService.bat` | Stops the service and waits until it has stopped |
| `-q <conf>` | `--query` | `queryService.bat` | Checks the configuration and shows the service's state |
| `-r <conf>` | `--remove` | `uninstallService.bat` | Removes the service (stop it with `-p` first) |
| | `--help`, `--version` | | Prints the usage or the version |
| | `--service <conf>` | | Runs as the service; only the Service Control Manager runs this |

`nyajsw.bat` and the named scripts fill in the installation's `conf\wrapper.conf` when the switch
comes without a file. They read these environment variables:

| Variable | Meaning |
|---|---|
| `NYAJSW_JAVA` | The `java.exe` that runs the wrapper; it must be Java 21 or later |
| `JAVA_HOME` | Used when `NYAJSW_JAVA` is not set: its `bin\java.exe` |
| `NYAJSW_OPTS` | Options for the wrapper JVM of this command, such as `-Dwrapper.debug=true` |

### Exit codes

| Code | Meaning |
|---|---|
| `0` | The command did what it was asked |
| `1` | An unexpected error, a launch failure or a usage error |
| `2` | The configuration could not be loaded or is invalid |
| `3` | A Windows service operation failed (the message gives the Windows error) |
| `4` | The command needs a platform this one is not, such as a Windows service elsewhere |

In console mode the exit code is the application's own, which may coincide with these values; the
wrapper log says which one it was. A service that stops with an error reports the same code to
Windows as `SERVICE_EXIT_CODE` (see [Troubleshooting](#troubleshooting)).

## The configuration file

nyajsw reads the YAJSW format, so an existing `wrapper.conf` works as it is.

- **Lines**: `key=value`, split at the first `=`. Lines starting with `#` or `!` are comments. A
  line ending in `\` continues on the next line.
- **Escapes**: a backslash before a space or one of `\ , " ' # = : !` is an escape, as in YAJSW
  (so `\\` is one backslash). Any other backslash is kept, so `C:\app\lib` reads as written.
  Forward slashes avoid the question: `C:/app/lib`. Watch a trailing backslash: `C:\app\` at the
  end of a line continues it on the next one, so write `C:\app\\` or `C:/app/`.
- **Variables**: `${name}` inserts the value of another key, a system property of the wrapper JVM
  or an environment variable, in that order. `${env:NAME}` and `${sys:name}` pick one source. Any
  key can serve as a variable, `wrapper.*` or not. A variable that names nothing known is kept as
  written, with a warning. Values are resolved again at every start.
- **Includes**: `include = <file>` and `#include.required <file>` add another file and fail if it
  is missing; `#include <file>` adds it if it exists. Paths are relative to the including file.
- **Numbered keys** such as `wrapper.java.classpath.<n>` are taken in the order of their numbers;
  gaps are allowed.
- **Precedence** (highest first): system properties of the wrapper JVM (`-Dwrapper.debug=true`
  through `NYAJSW_OPTS`, or `wrapper.ntservice.additional.<n>` for the service) → the
  configuration file and its includes → built-in defaults.
- **Relative paths**: the wrapper's own (log file, pid files, `wrapper.tmp.path`) are relative to
  the configuration file's directory; the application's (jar, classpath, library path, probe
  files) to `wrapper.working.dir`, whose default is also the configuration file's directory.
- **Encoding**: UTF-8; a file that is not valid UTF-8 is read as windows-1252, with a warning.
  `wrapper.conf.encoding` names another encoding.

### Warnings and errors

nyajsw never ignores a `wrapper.*` key silently. Every key is declared once in a registry, with
its status, default and meaning; [COMPATIBILITY.md](docs/COMPATIBILITY.md) is generated from it.
Keys outside `wrapper.*` are variables for `${...}` and get no warning.

| Status | What happens |
|---|---|
| supported | The key works as YAJSW documents it, with any difference in its notes |
| planned | Not implemented yet; a warning names the key |
| ignored | Not needed by nyajsw (such as `wrapper.java.mainclass`, `wrapper.port`); a warning |
| unsupported | Not implemented: out of scope (tray, Groovy scripts, JMX console, …) or in the backlog; a warning |
| unsupported, fatal | When set to a non-empty value, the wrapper stops with exit code 2, because ignoring the key would change what the service does (such as `wrapper.app.account`) |

An unknown `wrapper.*` key also gets a warning, often the sign of a misspelling. Values that do not
fit their key's type (a word where a number of seconds belongs), Groovy expressions and keystore
references in `${...}` are errors. All errors are reported together, each with its file and line.

## Running the application

| Key | Default | Meaning |
|---|---|---|
| `wrapper.java.command` | the wrapper's own `java` | The Java that runs the application; give it with its directory |
| `wrapper.java.app.mainclass` | | The main class, with the classpath below |
| `wrapper.java.app.jar` | | An executable jar, instead of a main class |
| `wrapper.java.classpath.<n>` | | Classpath entries; `*` and `?` match within a folder, a whole `**` segment at any depth |
| `wrapper.java.additional.<n>` | | One JVM option or `-D` property per key |
| `wrapper.java.initmemory`, `wrapper.java.maxmemory` | | Heap in MB (`-Xms`, `-Xmx`) |
| `wrapper.java.library.path.<n>` | | Entries of `java.library.path` |
| `wrapper.app.parameter.<n>` | | One program argument per key |
| `wrapper.app.env.<NAME>` | | An environment variable; an empty value removes an inherited one |
| `wrapper.working.dir` | the configuration file's directory | The application's working directory |
| `wrapper.java.pidfile`, `wrapper.pidfile` | | Files for the application's and the wrapper's process ids |

Two things trip up configurations written for other wrappers:

- **One option per key.** `wrapper.java.additional.1=-Xms1g -Xmx2g` passes a single argument,
  which the JVM rejects. Use two keys. Quotes around a whole option, or around what follows its
  first `=` (`-Dapp.home="C:/My App"`), are removed.
- **Give `wrapper.java.command` with its directory.** Windows looks up a bare `java` in the
  wrapper's own JDK first, so the application would run on the wrapper's Java 21.

The application's standard output and error are captured into the wrapper log. nyajsw starts the
application with its console encoding set to UTF-8, so that its output reaches the log intact,
unless a `wrapper.java.additional.<n>` sets it.

## Restarts and the application's exit

| Key | Default | Meaning |
|---|---|---|
| `wrapper.on_exit.<code>` | | What to do when the application exits with this code: `RESTART`, `SHUTDOWN` or `STOP` |
| `wrapper.on_exit.default` | `SHUTDOWN` | The action for codes without their own key, 0 included |
| `wrapper.restart.delay` | `5` | Seconds to wait before a restart |
| `wrapper.max_failed_invocations` | `5` | Failed invocations in a row after which the wrapper gives up |
| `wrapper.successful_invocation_time` | `60` | Seconds an invocation must run to count as successful |
| `wrapper.startup.delay` | `0` | Seconds to wait before the first start |
| `wrapper.startup.timeout` | `30` | Seconds the application has to become ready; `0` means no limit |
| `wrapper.control` | `TIGHT` | How the lifetimes of the wrapper and the application are tied |

An invocation fails when it cannot be started, does not become ready within
`wrapper.startup.timeout`, or ends within `wrapper.successful_invocation_time`. A successful one
resets the count. An application that ends because of a stop (a service stop or Ctrl+C) is never
restarted.

`wrapper.control` ties the lifetimes of the wrapper and the application together:

| Value | When the wrapper stops | Exit action `STOP` |
|---|---|---|
| `TIGHT` | the application is stopped | ends the wrapper, like `SHUTDOWN` |
| `APPLICATION` | the application is stopped | the wrapper keeps running without the application |
| `WRAPPER` | the application keeps running | ends the wrapper, like `SHUTDOWN` |
| `LOOSE` | the application keeps running | the wrapper keeps running without the application |

`RESTART` and `SHUTDOWN` do the same under every value. Under `TIGHT` and `APPLICATION` the
processes the application starts run in a Windows job object and end when the wrapper's
supervision ends, so a helper started by one invocation survives a restart of the application but
not the end of the service (see
[Windows service](docs/windows-service.md#processes-the-application-starts)).

## Stopping the application

A stop runs the strategies of `wrapper.stop.strategy` in order:

- **`command`** (first by default when `wrapper.stop.command` is set): runs that command, such as
  a `.bat` file that calls the application's own shutdown, to ask the application to end.
- **`agent`** (the default while the agent is on): the agent ends the application's JVM with
  `System.exit(0)`, so its shutdown hooks run and the exit code is 0.
- **`terminate`** (always last): once the stop budget is used up, the application's whole process
  tree is ended.

The stop budget is `wrapper.shutdown.timeout` (30 s by default) plus `wrapper.jvm_exit.timeout`.
Without the agent or a stop command nothing asks a service's application to end, since a service
receives no Ctrl+C: the wrapper waits out the budget and then terminates it, and the log warns
about that at every start. In console mode the application receives the Ctrl+C itself and has the
budget to finish.

The graceful steps share the budget equally, so with the default `command,agent,terminate` the
command has half of it before the agent ends the JVM.

```properties
wrapper.stop.command=bin/stop.bat --port 8005
```

The command is split and run as `wrapper.readiness.command` is: without a shell, in
`wrapper.working.dir` (a relative program too), with the wrapper's environment, its output
discarded and its input closed; Windows runs a `.bat` or `.cmd` file through `cmd.exe`. Its exit
code is logged and changes nothing else: the wrapper waits out the command's share even when it
fails or cannot be run, so a mistyped path costs that time before the next step. It is ended,
with what it started, when its share runs out, when the application has ended or when the wrapper
ends, so it is a way to ask the application to stop, not a hook that runs after the stop: work
that must follow the stop belongs in a shutdown hook of the application. A command that ends by
itself leaves behind what it started in the background (`start` in a `.bat` file).

YAJSW's stop programs (`wrapper.stop.conf`) and Tanuki's `WrapperStartStopApp` stop class are not
called; `wrapper.stop.command` can run the same stop program as a command.

## Readiness and liveness

YAJSW counts an application as started as soon as its process runs. nyajsw does the same by
default, but can wait for something better, and keep checking afterwards.

| Probe | Passes when |
|---|---|
| `process` | The application's process runs (the readiness default) |
| `tcp` | A TCP connection to `wrapper.readiness.tcp.host`:`port` can be opened |
| `http` | A `GET` of `wrapper.readiness.http.url` answers with an accepted status (`200-399` by default) |
| `log` | A new line of the application's output, or of a log file, matches a regular expression |
| `file` | A file or directory exists |
| `command` | A command, run without a shell, exits with 0 |
| `agent` | The agent in the application's JVM answers a ping (the liveness default while the agent is on) |

- **Readiness** (`wrapper.readiness.type`, a comma-separated list that must all pass): until it
  passes, a service shows as *starting*. If it does not pass within `wrapper.startup.timeout`, the
  application is terminated and the start counts as failed.
- **Liveness** (`wrapper.liveness.type`): after `wrapper.liveness.failures` failed checks in a row
  (3 by default, one every `wrapper.liveness.interval`, 10 s), the application is terminated, then
  restarted (`wrapper.liveness.action=RESTART`) or the wrapper ends with an error (`SHUTDOWN`).
- YAJSW's `wrapper.ping.interval` and `wrapper.ping.timeout` set the timing of the `agent` liveness
  check, so a migrated configuration keeps its heartbeat.

For example, an application that is ready once it logs its start-up line, and is restarted when it
stops answering on its port:

```properties
wrapper.readiness.type=log
wrapper.readiness.log.pattern=Server started in \d+ ms
wrapper.liveness.type=tcp
wrapper.readiness.tcp.port=8080
wrapper.liveness.interval=15
```

Each probe's settings live under `wrapper.readiness.<type>.*` and serve readiness and liveness
alike. [Probes and the agent](docs/probes.md) describes every probe, the agent, the timing rules
and the log messages.

## Logging

| Key | Default | Meaning |
|---|---|---|
| `wrapper.logfile` | `wrapper.log` beside the configuration | The log file; a blank value turns it off. `YYYYMMDD` in the name is the date, `ROLLNUM` the number of a rolled copy |
| `wrapper.logfile.loglevel` | `INFO` | `NONE`, `FATAL`, `ERROR`, `STATUS`, `INFO` or `DEBUG` |
| `wrapper.logfile.format` | `LPTM` | Fields of a line: `L` level, `P` prefix (`wrapper` or `jvm N`), `D` thread, `T` time, `Z` time with milliseconds, `M` message |
| `wrapper.logfile.rollmode` | by size only | When the file rolls besides by size: `JVM` (also at each start of the application), `WRAPPER` (also at each start of the wrapper), `DATE` (a file per day; the name needs `YYYYMMDD`). `SIZE` is the same as not setting it; `NONE` never rolls, not even by size |
| `wrapper.logfile.maxsize` | `0` | Size at which the file rolls, such as `10M`; `0` turns rolling by size off |
| `wrapper.logfile.maxfiles` | all | Rolled files kept |
| `wrapper.logfile.maxdays` | `-1` | Days of files kept under `DATE`; `-1` keeps all |
| `wrapper.console.loglevel`, `wrapper.console.format` | `INFO`, `PM` | The same for the console in console mode |
| `wrapper.debug` | `false` | Adds debug messages; `wrapper.debug.level` (0 to 3) chooses how many |

The log holds the wrapper's own messages and the application's output, each line prefixed with
`wrapper` or `jvm <n>`, the number of the application's invocation. Times are local, in the format
`yyyy/MM/dd HH:mm:ss`.

A service also has a **bootstrap log**, `nyajsw-bootstrap-<service name>.log` beside the
configuration file. It is created only when something goes wrong before the wrapper log is open,
or with the wrapper log itself, so a healthy service has none. When the service account may not
create files there, it goes to the account's temporary directory instead (`C:\Windows\Temp` for
`LocalSystem`).

## The Windows service

- **Install** with `-i` from an Administrator prompt. The service runs the `javaw.exe` of the Java
  that installed it (or `wrapper.ntservice.java.command`), with `--service <wrapper.conf>`.
- **Account**: `LocalSystem` unless `wrapper.ntservice.account` names another one: the built-in
  `NT AUTHORITY\LocalService` or `NetworkService`, a virtual `NT SERVICE\<name>`, or a local
  (`.\name`) or domain (`DOMAIN\name`) account with `wrapper.ntservice.password`. `-i` grants such
  an account the right to log on as a service.
- **Start type**: `wrapper.ntservice.starttype`, one of `AUTO_START` (the default),
  `DELAYED_AUTO_START`, `DEMAND_START` or `DISABLED`. Dependencies go in
  `wrapper.ntservice.dependency.<n>`.
- **Starting and stopping**: the service is *starting* until the application is first ready, and
  *running* from then on, also through later restarts of the application. Windows refuses to stop
  a service that is still starting (error 1061).
- **System shutdown**: the service is asked to stop early, and Windows gives it the stop budget
  plus 20 s.
- **Logging off** does not end the application: with `wrapper.ntservice.reduce_signals=true`, the
  default, the application JVM is started with `-Xrs`.
- **What needs a reinstall**: the `wrapper.ntservice.*` keys (except
  `wrapper.ntservice.reduce_signals`), `wrapper.tmp.path`, the stop timeouts, and the locations of
  the configuration file, the nyajsw jars and the JDK that ran `-i` are copied into the service's
  registration at `-i`.
  After changing them, run `-p`, `-r` and `-i` again. Everything else is read at every start.
- **Recovery actions**: nyajsw restarts the application itself. To have Windows restart the
  service once nyajsw has given up, set recovery actions and the failure flag after `-i`:

  ```
  sc failure <name> reset= 86400 actions= restart/60000
  sc failureflag <name> 1
  ```

[Windows service](docs/windows-service.md) covers accounts, permissions, logs and the native
library in detail.

## Security

- **Protect the installation.** Whoever can change the configuration, its includes, the nyajsw
  jars or the JDKs decides what the service runs, as `LocalSystem` by default. Keep them where only
  Administrators may write, such as under `C:\Program Files`. A folder created directly under
  `C:\` lets every authenticated user change files until that inherited permission is removed.
- **Passwords** (`wrapper.ntservice.password` and every other `*.password` key) are masked in the
  log, in messages and in the logged command line, including copies made with `${...}`. The
  service's password is stored by Windows. The rest of the service's command line, including
  `wrapper.ntservice.additional.<n>`, can be read by every local user: put no secret there.
- **The agent channel** listens on `127.0.0.1` only, on a new port with a new random token for
  every launch, and accepts a single connection. The token reaches the application through the
  `NYAJSW_AGENT_TOKEN` environment variable, never the command line. No firewall rule is needed.
- **No shell.** Configuration values are passed to processes as separate arguments, never through
  a shell. (A `command` probe that names a `.bat` or `.cmd` file runs through `cmd.exe`, as Windows
  requires.)

## Troubleshooting

Start with `nyajsw -q <wrapper.conf>`: it checks the configuration without changing anything. Then
look at the wrapper log, and for a service that does not start at all, at its bootstrap log.

| Symptom | Likely cause |
|---|---|
| `-i`, `-t`, `-p` or `-r` ends with "run as Administrator" | The command needs an elevated prompt |
| The service does not respond (error 1053) | The wrapper could not start: see the bootstrap log, which is named after the configuration file (`nyajsw-bootstrap-wrapper.log`) when the service name is not known yet. A common cause is JNA's native library, which cannot be unpacked into the temporary directory or is blocked there: set `wrapper.tmp.path` and reinstall |
| The service stops with error 1066 | The application could not be kept running, or ended with a code other than 0. `sc query <name>` shows the code as `SERVICE_EXIT_CODE`; `2` is a configuration error |
| The service will not stop (error 1061) | It is still starting: the application has not passed readiness yet |
| The service does not log on (error 1069) | Wrong password, or the account lost the right to log on as a service (a domain policy can replace the local grant) |
| `-i` fails with error 1072 | The old service is marked for deletion: close the Services console and anything else that has it open, then wait until `sc query <name>` reports error 1060 |
| The application runs on the wrong Java | `wrapper.java.command` is a bare `java`; give it with its directory |
| The JVM rejects an option such as `-Xms1g -Xmx2g` | Two options in one `wrapper.java.additional.<n>`: use one key each |
| A warning such as `unknown key …` or `… is not supported by nyajsw` | The key has no effect; [COMPATIBILITY.md](docs/COMPATIBILITY.md) says why |
| Running the service's command line by hand fails with "the process was not started as a service" | `--service` is for the Service Control Manager only; use `-c` to run in the console |

For more detail, set `wrapper.debug=true` in the configuration file. For a single command without
editing the file, run `set NYAJSW_OPTS=-Dwrapper.debug=true` first in the same prompt.

## Differences from YAJSW

The main ones; [Moving from YAJSW](docs/migration-from-yajsw.md#what-behaves-differently) lists
them all.

- The service is a reinstall, not a jar swap: remove it with YAJSW, install it with nyajsw from the
  same file.
- The wrapper runs on Java 21; `wrapper.ntservice.java.command` defaults to the installing Java's
  `javaw.exe`, not to the application's Java.
- No bootstrap class in the application JVM: nyajsw starts the main class itself and uses the agent
  for heartbeats and stops.
- "Running" means the readiness probes passed; `wrapper.ntservice.autoreport.startup=false` has no
  effect.
- `${...}` is resolved at every start, not stored at install.
- The default log file is `wrapper.log` beside the configuration file, with nyajsw's own time
  format.
- Out of scope, warned about and ignored: the system tray, Groovy scripts, and the JMX and web
  consoles. Output filters and POSIX daemons are not implemented yet either.
- Some keys stop the wrapper instead of being ignored, because ignoring them would change what the
  service does: running the application under another account (`wrapper.app.account`),
  `wrapper.config.script.<name>`, `wrapper.condition.script` and `wrapper.windows.cluster.*`.

## Documentation

| Guide | Contents |
|---|---|
| [Moving from YAJSW](docs/migration-from-yajsw.md) | Reinstalling a YAJSW service with nyajsw, the settings to carry over, the warnings, the differences |
| [Windows service](docs/windows-service.md) | The service commands, accounts, logs, the application's JVM, JNA |
| [Probes and the agent](docs/probes.md) | Readiness and liveness checks, the agent, stop strategies |
| [Compatibility matrix](docs/COMPATIBILITY.md) | Every configuration key with its status, default and notes (generated) |
| [Implementation plan](docs/IMPLEMENTATION_PLAN.md) | Goals, architecture, phases and their status, decisions, open questions |

## Building

Requirements: JDK 21 or later and Maven 3.9 or later.

```bash
mvn -q verify
```

The distribution is then `nyajsw-dist/target/nyajsw-<version>.zip`. The build runs the unit tests
and tests of the packaged zip; both run on any operating system without administrator rights.
Tests that talk to the real Windows Service Control Manager are in the TestNG group
`windows-integration`, which the profile of the same name includes. It needs an elevated
(Administrator) Windows shell:

```bash
mvn -q verify -Pwindows-integration
```

`docs/COMPATIBILITY.md` is generated from the key registry, and a test fails when it is out of
date. After changing a key, regenerate it:

```bash
mvn -q -pl nyajsw-wrapper -am test -Dtest=CompatibilityDocTest -Dsurefire.failIfNoSpecifiedTests=false -Dnyajsw.docs.update=true
```

## Modules

| Module | Contents |
|---|---|
| `nyajsw-agent` | The optional `-javaagent` loaded into the application JVM, and the shared wrapper/agent protocol (Java 8 bytecode, no runtime dependencies) |
| `nyajsw-wrapper` | The wrapper: configuration, launcher, supervisor, probes, logging, platform integration, CLI |
| `nyajsw-dist` | The distribution zip: the shaded `nyajsw.jar`, the agent's jar, `bin\*.bat`, the configuration template |

## Contributing

Before changing anything, read two documents:

- [AGENTS.md](https://github.com/JacoboSanchez/nyajsw/blob/main/AGENTS.md), the working agreement: build rules, review process, coding and testing
  conventions;
- the [implementation plan](docs/IMPLEMENTATION_PLAN.md): goals, architecture, phases, decisions.

## License

[Apache License 2.0](LICENSE). nyajsw contains no code or documentation from YAJSW or the Tanuki
Java Service Wrapper. It reuses only public interface names, for compatibility: configuration
keys and their documented meaning, command-line switches and script names. The distribution
bundles JNA, used under the Apache License 2.0; see `THIRD-PARTY.txt` in the zip.
