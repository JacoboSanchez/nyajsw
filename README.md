# nyajsw

**nyajsw** ("new YAJSW") is a clean-room Java 21 service wrapper that will run a Java application as
a Windows service. It is meant to replace [YAJSW](https://yajsw.sourceforge.io/) (Yet Another Java
Service Wrapper), which is no longer maintained. It is designed to read existing `wrapper.conf` files
without edits, warning about anything it does not support, so a deployment migrates by swapping the
wrapper rather than rewriting its configuration. On top of that it will add readiness and liveness
probes, so "the service started" can mean "the application answers", not just "a process exists".

> **Status: early development.** Only the build skeleton exists so far; nothing is usable yet.
> Progress is tracked phase by phase in the [implementation plan](docs/IMPLEMENTATION_PLAN.md).

## Building

Requirements: JDK 21 or later and Maven 3.9 or later.

```bash
mvn -q verify
```

Tests that talk to the real Windows Service Control Manager will live in the TestNG group
`windows-integration` (none exist yet). The group is excluded by default; the profile of the same
name includes it and needs an elevated (Administrator) Windows shell:

```bash
mvn -q verify -Pwindows-integration
```

## Modules (planned contents)

| Module | Contents |
|---|---|
| `nyajsw-agent` | Optional `-javaagent` loaded into the application JVM, and the shared wrapper/agent protocol (Java 8 bytecode, no runtime dependencies) |
| `nyajsw-wrapper` | The wrapper: configuration, launcher, supervisor, probes, logging, platform integration, CLI |
| `nyajsw-dist` | The distribution archive: jars, `bin/*.bat` scripts, configuration templates |

## Contributing

Read [AGENTS.md](AGENTS.md), the working agreement (build rules, review process, coding and testing
conventions), and the [implementation plan](docs/IMPLEMENTATION_PLAN.md) (goals, architecture,
phases, decisions) before changing anything.

## License

[Apache License 2.0](LICENSE). nyajsw contains no code or documentation from YAJSW or the Tanuki
Java Service Wrapper; only public interface names are reused for compatibility: configuration keys
and their documented meaning, command-line switches and script names.
