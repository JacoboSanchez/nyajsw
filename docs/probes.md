# Readiness and liveness probes

A probe is a check of the application. nyajsw uses probes for two questions:

- **Readiness**: has the application started? Until it has, a service shows as *starting*. Only
  then does it show as *running*.
- **Liveness**: is the running application still healthy? If it keeps failing, nyajsw restarts it
  or stops.

YAJSW treats an application as started as soon as its process runs. nyajsw does the same by
default, but you can make it wait for something better: an open port, an HTTP answer, a log
line, a file, a command, or the nyajsw agent inside the application's JVM.

All keys below go in `wrapper.conf`. The full list of keys, with their defaults, is in
[COMPATIBILITY.md](COMPATIBILITY.md).

## Readiness

```properties
wrapper.readiness.type=http
wrapper.readiness.http.url=http://127.0.0.1:8080/health
```

| Key | Default | Meaning |
|---|---|---|
| `wrapper.readiness.type` | `process` | The probes to check, separated by commas. All of them must pass. |
| `wrapper.readiness.interval` | `1` | Seconds between two checks, at least 1. |
| `wrapper.startup.timeout` | `30` | Seconds the application has to become ready; `0` means no limit. |

The first check runs right after the launch. Each later check runs one interval after the previous
one has answered.

If the application is not ready within `wrapper.startup.timeout`:

- nyajsw terminates it at once.
- It counts as a failed start under `wrapper.max_failed_invocations`.
- nyajsw starts it again after `wrapper.restart.delay` if the restart policy allows.

The wrapper log shows the last check that failed. A service keeps showing as *starting* during
that time, and Windows refuses to stop a service that is starting (error 1061).

A service whose first start keeps failing stays *starting* through its restarts. It can only be
stopped once `wrapper.max_failed_invocations` is used up. After `wrapper.startup.delay` plus
`wrapper.startup.timeout` plus 30 s, 60 s by default, the wrapper stops reporting progress, and
Windows shows the service as not responding. Do not combine `wrapper.startup.timeout=0` with a
probe that may never pass in a service: such a service can never be stopped through Windows.

## Liveness

```properties
wrapper.liveness.type=http
# the http probe's settings are shared with readiness: see below
wrapper.readiness.http.url=http://127.0.0.1:8080/health
wrapper.liveness.interval=15
wrapper.liveness.failures=4
```

| Key | Default | Meaning |
|---|---|---|
| `wrapper.liveness.type` | `agent` when the agent is on, else none | The probes to check while the application runs. |
| `wrapper.liveness.interval` | `10` | Seconds between two checks, at least 1. |
| `wrapper.liveness.failures` | `3` | Failed checks in a row that trigger the action, at least 1. |
| `wrapper.liveness.action` | `RESTART` | `RESTART` or `SHUTDOWN`. |

An empty `wrapper.liveness.type` counts as not written, so the `agent` default stays, and there is
no type called `none`. To turn the default liveness check off, write `wrapper.ping.timeout=0`,
which keeps the agent for stops, or turn the agent off with `wrapper.agent.enabled=false`.

- **Timing**: the first check runs one interval after the application became ready. The next one
  waits for the previous result, so a slow check delays the next instead of piling up.
- **Recovery**: a check that passes resets the count. The log says when the application "passes
  its liveness check again".
- **Exits**: once the process has ended, checks no longer count. Its exit code then decides what
  happens, through `wrapper.on_exit.*`, as usual.

After `wrapper.liveness.failures` failed checks in a row, nyajsw terminates the application at
once. It does not ask the application to stop first, because one that fails its checks may not
answer that either. Then the action applies:

- **`RESTART`**: nyajsw starts the application again after `wrapper.restart.delay`, whatever
  `wrapper.on_exit.*` says; only the restart policy can prevent it. Its run time is counted up to
  the first failed check. If that check came within `wrapper.successful_invocation_time` of the
  launch, the run counts as a failed invocation under `wrapper.max_failed_invocations`, however
  long the checks took to give up.
- **`SHUTDOWN`**: nyajsw waits for the application to end, then ends supervision as failed. In
  console mode the exit code is the application's, and never 0. A service stops with error 1066,
  and `sc query` shows the code. Windows recovery acts on such a stop only if the service has
  recovery actions and "Enable actions for stops with errors" is set (`sc failure` and
  `sc failureflag <name> 1`). nyajsw does not set these yet.

## Settings shared by readiness and liveness

Each probe type has one set of settings, under `wrapper.readiness.<type>.*`. They apply wherever
the type is listed, in `wrapper.readiness.type` or `wrapper.liveness.type`. You cannot check
readiness on one port and liveness on another.

Type names ignore case.

Some mistakes stop the wrapper with a configuration error (exit code 2) before the application is
started:

- an unknown type;
- a type listed without its required key, such as `tcp` without `wrapper.readiness.tcp.port`;
- a value that cannot work, such as a port of 70000, an invalid pattern, or 0 for one of the two
  intervals, `wrapper.readiness.http.timeout`, `wrapper.readiness.command.timeout` or
  `wrapper.liveness.failures`.

Values are checked even for types that are not listed. These errors come up when the wrapper
starts the application, not when the service is installed: a service installed with one stops at
its start with error 1066 and code 2.

A probe list that contains the value of a `*.password` key, copied in with `${...}`, is ignored
with a warning, and the default applies.

## The probe types

### `process`

Passes while the application's process runs. This is the default for readiness, and it is all
YAJSW checks.

### `tcp`

Passes when a TCP connection to the host and port can be opened; the connection is closed at once.

| Key | Default | Meaning |
|---|---|---|
| `wrapper.readiness.tcp.host` | `127.0.0.1` | Host to connect to; a name is looked up on every check. |
| `wrapper.readiness.tcp.port` | (required) | Port to connect to, 1 to 65535. |

A connection attempt may take up to 5 seconds.

### `http`

Sends a `GET` and passes when the answer's status is accepted.

| Key | Default | Meaning |
|---|---|---|
| `wrapper.readiness.http.url` | (required) | An absolute `http://` or `https://` URL with a host. |
| `wrapper.readiness.http.status` | `200-399` | Accepted status codes, as codes and ranges separated by commas, such as `200,204` or `200-299`. |
| `wrapper.readiness.http.timeout` | `5` | Seconds the whole exchange may take, connecting and the body included. |

Things to know:

- **Redirects**: they are not followed. A `302` is judged by its own status, so the default range
  accepts it.
- **Connection**: the request goes straight to the host over HTTP/1.1. It ignores any proxy the
  wrapper's JVM is set up with, since the target is usually the application on the same machine.
- **TLS**: `https` trusts what the wrapper's JVM trusts. A self-signed certificate fails, and the
  log quotes the TLS error.
- **Credentials**: user information in the URL (`http://user:password@host/...`) is not sent, and
  you get a warning about it. Log messages show the URL without user information, query or
  fragment.
- **Hung applications**: one that sends its headers and then hangs fails the check when the
  timeout runs out.

### `file`

Passes while a file or directory exists.

| Key | Default | Meaning |
|---|---|---|
| `wrapper.readiness.file.path` | (required) | The path, relative to `wrapper.working.dir`. |

A file left over from an earlier run counts too, and the wrapper never removes it. The first
check runs right at the launch, before the application's code does anything, so the file must
already be gone by then: the application should delete it when it stops. A crashed application
leaves it behind, and its restart is judged ready at once. For readiness, prefer `tcp`, `http` or
`log`.

### `command`

Runs a command and passes when it exits with 0.

| Key | Default | Meaning |
|---|---|---|
| `wrapper.readiness.command` | (required) | The program and its arguments. |
| `wrapper.readiness.command.timeout` | `10` | Seconds one run may take; after that it is killed, with everything it started. |

How the command runs:

- **No shell**: the value is split into arguments at blanks, and double quotes group blanks into
  one argument, for example `"C:/Program Files/check.exe" --port 8080`. There is no escape
  character. Before that, `wrapper.conf` reads backslashes as its own escapes and expands
  `${...}`, so write Windows paths with forward slashes.
- **Program lookup**: a program written with a directory is relative to `wrapper.working.dir`; a
  bare name is looked up as Windows does, `PATH` included.
- **Environment**: it runs in `wrapper.working.dir` with the wrapper's environment, not the
  application's `wrapper.app.env.*`.
- **Input and output**: its output is discarded, and its input is closed at once, so it cannot
  wait for either.
- **Batch files**: Windows runs a `.cmd` or `.bat` program through `cmd.exe`, which expands
  `%VARIABLES%` in its arguments.
- **Logging**: log messages name the program, never its arguments, which may hold credentials.

### `log`

Passes when a line that came since the previous check contains a match of the pattern. For the
first check of an invocation, that means since its launch.

| Key | Default | Meaning |
|---|---|---|
| `wrapper.readiness.log.pattern` | (required) | A Java regular expression, matched anywhere in a line. |

`wrapper.conf` reads the value first: `${...}` is expanded, and a backslash before a space, before
another backslash, or before one of `, " ' # = : !` is its escape. `\d` and `\s` work as
written; a pattern for one literal backslash is written `\\\\`.
| `wrapper.readiness.log.file` | (none) | A file to read, relative to `wrapper.working.dir`; without it, the application's captured output. |

What it reads:

- **Captured output**: by default, the application's standard output and error, as the wrapper
  logs them, with secrets masked. Long lines come in pieces. The wrapper keeps the latest 10,000
  lines, and at most 2 million characters, of each run. An application that logs more than that
  between two checks pushes its oldest lines out, and the detail of a failed check says how many it
missed. A pattern cannot match across two pieces of a long line.
- **A log file**: with `wrapper.readiness.log.file`, the lines appended to that file since the
  launch, read as UTF-8. A pattern in plain ASCII matches in any ASCII-based encoding.
  - A file that becomes shorter than what was read, after a log rotation, is read again from its
    start.
  - A file that does not exist yet is read in full once it appears.
  - At most 16 MiB are read per check; of more, the older part is skipped.
  - Lines are matched up to 64 KiB.

As readiness, look for the line the application writes once it is up, such as
`Started .* in \d+`. As liveness, look for a message the application writes regularly. It must
come at least once per `wrapper.liveness.interval`, since every check needs a new match.

One pattern serves readiness and liveness, so to look for both use an alternation, such as
`Started|alive`.

### `agent`

Passes when the nyajsw agent in the application's JVM has connected and answers a ping. This is
the default for liveness, and the next section describes the agent.

What it reports, depending on where the run stands with its agent:

| Where the run stands | Readiness | Liveness |
|---|---|---|
| Runs without the agent | passes | passes |
| The agent has not connected yet | fails | passes |
| Connected, answers within the timeout | passes | passes |
| Connected, does not answer in time | fails | fails |
| The session has ended | fails | fails |

A run counts as without the agent when the agent is off or not injected, as described in the next
section. It also counts that way when the agent never connected: the wrapper listens for one
minute after the launch, then warns. So with a `wrapper.startup.timeout` over 60 s, an `agent`
readiness probe passes after that minute even if the agent never connected.

The ping timeout is `wrapper.ping.timeout` (see below). When it is 0, no ping is sent: a connected
agent passes, and only a session that ended fails.

## The agent

The agent is a small jar, `nyajsw-agent.jar`, that the wrapper loads into the application's JVM
with `-javaagent`. It starts one background thread, connects to the wrapper over `127.0.0.1` and
answers its pings. When the wrapper stops the application, the agent ends the JVM with
`System.exit(0)`. The application's shutdown hooks run, and its exit code is 0. It works on Java 8
and later. Besides `-javaagent`, the application's JVM gets the system property
`nyajsw.agent.port`, the environment variable `NYAJSW_AGENT_TOKEN` (which processes it starts
inherit) and a daemon thread named `nyajsw-agent`.

| Key | Default | Meaning |
|---|---|---|
| `wrapper.agent.enabled` | `true` | Load the agent into the application's JVM. |

When the agent is on:

- **Stop strategy**: the default `wrapper.stop.strategy` is `agent,terminate`. A stop asks the
  agent first, waits up to the stop budget (`wrapper.shutdown.timeout` plus
  `wrapper.jvm_exit.timeout`), then terminates the application.
- **Liveness**: the default `wrapper.liveness.type` is `agent`.

Without a connected agent, a stop still waits the budget before terminating. In console mode that
gives the application the time to finish after the Ctrl+C it receives too. A budget of 0 skips
the agent and terminates at once.

The agent is not injected in these cases:

- **Turned off**: `wrapper.agent.enabled=false`.
- **Missing module**: the application's Java runtime has no `java.instrument` module, as in some
  `jlink` images. The wrapper reads the runtime's `release` file and warns. It can do so only when
  `wrapper.java.command` names the runtime with its directory. For a bare `java` found on `PATH`
  it cannot tell, and such a runtime fails to start with `-javaagent`: set
  `wrapper.agent.enabled=false` for it.
- **No jar**: `nyajsw-agent.jar` cannot be found beside the wrapper, or its path holds `=`. The
  wrapper warns.
- **No port**: the wrapper cannot listen on a loopback port. It warns.

Once `wrapper.image` is supported (it is planned), an application started through it, which may
be any executable, will not get the agent either.

The agent never holds the application up. If it cannot reach the wrapper, or a security manager
forbids what it needs, it prints one line on standard error, and the application runs without it.

The channel is local only. The wrapper picks a new port and a new random token for every launch,
and passes the token through the environment variable `NYAJSW_AGENT_TOKEN`, never on the command
line. It accepts one connection that presents the token, then stops listening. YAJSW's
`wrapper.port`, `wrapper.port.min` and `wrapper.port.max` are ignored: nothing outside the machine
needs to reach this port, so no firewall rule is needed.

### YAJSW's ping settings

YAJSW checks its application by pinging it. nyajsw maps those settings onto the `agent` liveness
probe, so a migrated `wrapper.conf` that writes them keeps its timing. A file that does not write
them gets nyajsw's liveness defaults, not YAJSW's: a check every 10 s, pings of up to 30 s, and
3 failures in a row, so a hang is acted on after up to about two minutes.

| Key | Default | In nyajsw |
|---|---|---|
| `wrapper.ping.interval` | `5` | When written, and `wrapper.liveness.interval` is not, the liveness interval. |
| `wrapper.ping.timeout` | `30` | How long each ping may take; when written, the silence after which the application counts as hung. |

- **A written timeout** keeps YAJSW's meaning, provided the agent is the only liveness probe and
  `wrapper.liveness.failures` is not written. Each ping may then take up to one interval, or the
  timeout if that is shorter, and as many failed checks in a row act as fit into the timeout. For
  example,
  `wrapper.ping.interval=30` with `wrapper.ping.timeout=60` acts after one failed check, about 60
  seconds of silence.
- **Otherwise** the timeout is how long each ping may take, and `wrapper.liveness.failures` counts
  the failed checks.
- **A value of 0** for either key means "no heartbeat", as in files written for the Tanuki format:
  the `agent` liveness check is then off by default.
- **Both intervals written**: when both `wrapper.liveness.interval` and `wrapper.ping.interval`
  are set, the nyajsw key wins and the log warns about the other.

## Examples

A web application that is ready once its health URL answers, restarted when it stops answering:

```properties
wrapper.readiness.type=http
wrapper.liveness.type=http
wrapper.readiness.http.url=http://127.0.0.1:8080/actuator/health
wrapper.readiness.http.status=200
wrapper.startup.timeout=120
wrapper.liveness.failures=3
```

An application that is ready once it logs its start-up line, and stays watched by the agent:

```properties
wrapper.readiness.type=log
wrapper.readiness.log.pattern=Server started in \d+ ms
```

A server checked by its own tool, in which an unhealthy server stops the service with an error,
for Windows recovery to act on if the service is set up for it (see *Liveness*):

```properties
wrapper.liveness.type=command
wrapper.readiness.command="C:/Program Files/Example/bin/check.exe" --quiet
wrapper.readiness.command.timeout=20
wrapper.liveness.action=SHUTDOWN
```

An application that must not get the agent:

```properties
wrapper.agent.enabled=false
```

## Reading the log

| The log says | What it means |
|---|---|
| `jvm 1 is ready (http)` | The readiness probes passed; after the first start, a service shows as *running*. |
| `jvm 1 did not become ready within 30 s ... last check: ...` | The startup timeout ran out; the last check says why. |
| `jvm 1 failed its liveness check (1 of 3 in a row): ...` | A liveness check failed; the count is shown. |
| `jvm 1 failed 3 liveness checks in a row ...; terminating it` | The liveness action follows. |
| `the agent of jvm 1 has connected (protocol 1)` | The agent is in the application's JVM and talking to the wrapper. |
| `the agent of jvm 1 did not connect within 60 s` | The application runs without the agent: see *The agent*. |
| `nyajsw agent: cannot reach the wrapper on port ...` | On the application's standard error: the agent gave up, and the application runs without it. |
| `the agent's jar (nyajsw-agent.jar) is not found beside the wrapper ...` | The agent is not injected. |
| `the application's Java runtime has no java.instrument module ...` | The agent is not injected. |
| `... names the agent, but the application runs without it ...` | A stop strategy or probe list names the agent, which is not injected. |
| `wrapper.ping.interval is ignored: wrapper.liveness.interval sets the liveness interval` | Both intervals are written. |
| `the agent of jvm 1 is ending the application` | A stop through the agent was accepted. |
| `the agent of jvm 1 did not acknowledge the stop within ... s` | The agent did not answer the stop; the application is terminated once the stop budget is up. |
| `jvm 1 did not end within 30 s; terminating it (terminate)` | The stop budget ran out. |
| `refused a connection to the agent port of jvm 1: ...` | Something other than the agent connected to the wrapper's agent port. |

Some of these lines start with `warning: `. With `wrapper.debug=true` (and `wrapper.debug.level`
at its default of 3, or 2 or more), the log also shows the checks that passed, and readiness
checks that have not passed yet.
