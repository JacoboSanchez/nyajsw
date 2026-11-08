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
- Writes the wrapper log with the application's output, rolled by size, by date or at each start,
  as `wrapper.logfile.*` says. Passwords are masked in log lines and messages.

## Quick start

Requirements: Windows and Java 21 or later for the wrapper. The application may run on an older
Java.

1. Build the distribution (see *Building*) and unpack `nyajsw-<version>.zip`:

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
   [Moving from YAJSW](docs/migration-from-yajsw.md) instead of steps 3 and 4: its service must be
   removed first.
3. Try the application in the console; Ctrl+C stops it:

   ```
   bin\runConsole.bat
   ```

4. From an Administrator prompt, install and start the service:

   ```
   bin\installService.bat
   bin\startService.bat
   ```

The scripts run the wrapper on the Java that `NYAJSW_JAVA` names (a `java.exe`), else the one in
`JAVA_HOME`, else `java` from the `PATH`. The service is registered with that Java's `javaw.exe`,
unless `wrapper.ntservice.java.command` names another Java 21 or later.

## Documentation

| Guide | Contents |
|---|---|
| [Moving from YAJSW](docs/migration-from-yajsw.md) | Reinstalling a YAJSW service with nyajsw, the settings to carry over, the warnings, the differences |
| [Windows service](docs/windows-service.md) | The service commands, accounts, logs, the application's JVM, JNA |
| [Probes and the agent](docs/probes.md) | Readiness and liveness checks, the agent, stop strategies |
| [Compatibility matrix](docs/COMPATIBILITY.md) | Every configuration key with its status, default and notes (generated) |

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
