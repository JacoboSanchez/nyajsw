# AGENTS.md — working agreement for nyajsw

This file tells coding agents (and humans) how to work in this repository.
The *what* and *why* of the project live in [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md);
this file is about *how we work*. Read both before changing anything, and keep both up to date.

## 1. What this project is

**nyajsw** ("new YAJSW") is a clean-room, Java 21 service wrapper that runs a Java application as a
Windows service. It replaces [YAJSW](https://yajsw.sourceforge.io/) (Yet Another Java Service
Wrapper), which the project owner is moving away from, while staying **configuration-compatible**
with existing `wrapper.conf` files so current deployments migrate by swapping the wrapper, not by
rewriting their configuration.

Hard constraints:

| Topic | Rule |
|---|---|
| Language / runtime | Java 21 for the wrapper. No preview features. |
| Build | Maven (multi-module). `mvn -q verify` must pass before every commit. |
| Tests | TestNG only (no JUnit). Mockito for mocks. |
| Native access | JNA + `jna-platform`. Use the generic Windows Service Control Manager (SCM) API in `advapi32` (`OpenSCManager`, `CreateService`, `StartService`, `ControlService`, `QueryServiceStatusEx`, `StartServiceCtrlDispatcher`, …). Hand-written bindings only for functions `jna-platform` lacks. |
| Wrapped application | May run on an **older JVM** than the wrapper (`wrapper.java.command`). Anything injected into the application JVM (the agent module) targets **Java 8 bytecode** and has **zero dependencies**. |
| License | Apache-2.0. YAJSW lists Apache-2.0 alongside LGPLv3 (since 12.00) and Tanuki JSW is proprietary/GPL: **never copy code, comments, or documentation text from them**. Only public configuration key names and their documented meaning are reused (that is the compatibility surface). The rule holds whatever licence YAJSW uses. |
| Platform | Windows first. Everything OS-specific sits behind the `platform` abstraction so POSIX support can be added later without touching the core. |
| Wording | English everywhere: code, identifiers, comments, documentation, log messages, commit messages. |

## 2. Repository layout (target)

```
pom.xml                         parent: versions, plugin config, modules
nyajsw-agent/                   -javaagent loaded into the application JVM (Java 8 bytecode, no deps);
                                also defines the shared AgentProtocol used by the wrapper
nyajsw-wrapper/                 the wrapper: config, launcher, supervisor, probes, logging, platform, CLI
nyajsw-dist/                    assembly: nyajsw.jar, agent jar, bin/*.bat, conf/ templates, zip
docs/IMPLEMENTATION_PLAN.md     goals, architecture, phases (with status), decisions, open questions
docs/COMPATIBILITY.md           generated from the config key registry (do not edit by hand)
docs/*.md                       user guides (probes, migration from YAJSW, …)
```

Base package: `io.jsl.nyajsw` (see plan decision D11). Sub-packages are described in
plan §4.3. Create directories as the phases need them; do not pre-create empty structure.

## 3. Build and test

```bash
mvn -q verify
```

Windows-only integration tests (TestNG group `windows-integration`) talk to the real SCM. They are
excluded by default and need an **elevated** Windows shell:

```bash
mvn -q verify -Pwindows-integration
```

`docs/COMPATIBILITY.md` is generated from the key registry. A test fails when it is stale; regenerate
it with:

```bash
mvn -q -pl nyajsw-wrapper -am test -Dtest=CompatibilityDocTest -Dsurefire.failIfNoSpecifiedTests=false -Dnyajsw.docs.update=true
```

Until Phase 0 lands there is no build; the commands above describe the target.

## 4. Working rules

### 4.1 Java 21 compatibility

* `maven.compiler.release` is `21` for `nyajsw-wrapper` and `nyajsw-dist`, `8` for `nyajsw-agent`.
* Java 21 language features are welcome where they make code clearer (records, sealed interfaces,
  pattern matching for `switch`, text blocks). Prefer standard APIs such as `ProcessHandle` over JNA
  whenever they are sufficient. Do not use preview or incubator APIs.
* Do not depend on JDK-internal packages (`sun.*`, `jdk.internal.*`). `com.sun.management` is
  acceptable when guarded and documented.

### 4.2 One complete change per commit, reviewed before committing

A "complete change" is one plan item (or one user request) that compiles, has tests, passes
`mvn -q verify`, and updates the docs it affects. Workflow for every change:

1. Read the relevant plan section (§4 for design, §5 for the phase checklist). Pick **one** item.
2. Write or adjust TestNG tests together with the code.
3. Run `mvn -q verify` locally.
4. **Code review by a subagent before committing.** Launch a *fresh* reviewer (not a fork, so it is
   not anchored on your reasoning). Give it: the diff (`git diff` / `git diff --cached`), the list
   of touched files, the plan item, and a pointer to this file. Do not give it your conclusions.
   Ask for findings ordered by severity using the checklist in §4.3 below.
5. Fix every valid blocking/major finding; re-run `mvn -q verify`. Minor findings you choose to
   defer go into the plan's "Deferred review findings" list (end of §5) in the same commit, so
   nothing is silently dropped.
6. Update the plan: tick the checkbox, adjust the decisions log / compatibility matrix if the change
   touched them, bump "Last updated".
7. Commit and push to the branch assigned for the task. Never push to another branch.

Rules:

* Never batch unrelated changes into one commit. Never commit with red tests. Never commit
  generated or IDE files.
* Commit message format: `<area>: <imperative summary>` (≤ 72 chars), blank line, body explaining
  *why* and any trade-off, and a `Plan: P<phase>.<item>` line when applicable.
  Areas: `build`, `config`, `launch`, `lifecycle`, `probe`, `log`, `platform`, `agent`, `cli`,
  `dist`, `docs`, `test`.
* Do not put model names or tool chatter in commit messages, code, or docs. Trailers added
  automatically by the tooling are fine.

### 4.3 Review checklist (used by the review subagent)

1. **Correctness**: does the change do what the plan item says? Edge cases? Error paths? Win32
   error codes surfaced with `Kernel32Util.formatMessage`-style text?
2. **Scope**: nothing unrelated changed; no drive-by refactors or reformatting.
3. **Duplication / reuse**: is there an existing helper, probe, strategy or utility that already does
   this? (`grep` before concluding.) Should repeated logic be extracted?
4. **Design**: OS/JNA code behind the `platform` interfaces? New behaviour added as a new class plus a
   registry entry rather than edits spread across many files? Immutable config/model types?
5. **Tests**: TestNG, meaningful assertions, runnable on Linux/macOS/Windows without admin rights,
   Windows-only tests in group `windows-integration`, no sleeps where a fake clock would do.
6. **Config compatibility**: new keys declared in the key registry (origin, status, default,
   description); no hard-coded key strings elsewhere; YAJSW keys not renamed; `COMPATIBILITY.md`
   regenerated.
7. **Security**: passwords (`wrapper.ntservice.password`, `wrapper.app.password`) never logged or
   echoed; agent socket bound to loopback with a token; no shell interpolation of config values.
8. **Docs**: plan status, decisions, user docs updated where the behaviour changed.
9. **Java 21 / portability**: no preview APIs, no JDK internals, paths handled with `java.nio.file`.

### 4.4 Clean, maintainable, reusable code

* Small classes with one responsibility. Interfaces at the boundaries that vary: `Platform`,
  `ServiceController`, `ServiceHost`, `ScmApi`, `ProcessApi`, `Probe`, `StopStrategy`, `ConfigSource`.
* Prefer **adding a class and registering it** over editing many existing files. Extension points
  are registries/factories, not `if/else` chains spread across the codebase.
* **Search before you write.** If a helper exists, use it; if two call sites need the same logic,
  extract it. Duplication is a review blocker.
* Make targeted edits. Do not reformat, reorder, or rename things in files you are not changing for a
  functional reason. Small change → small diff.
* Immutability by default: configuration and model types are records or final classes; collections
  exposed as unmodifiable. No static mutable state (the only static state allowed is the JNA
  callback references the service host must keep alive, and that is documented in place).
* `System.exit` only in the CLI entry point (and in the agent's `STOP` handler, whose whole purpose
  is to end the application JVM); likewise `Runtime.halt`, used only by the CLI's console shutdown
  hook to exit with the application's code (plan D21). Everything else returns results or throws.
* Exceptions: a small hierarchy (`NyajswException` → `ConfigException`, `LaunchException`,
  `ServiceException`, `PlatformException` → `UnsupportedPlatformException`). Include the Win32 error
  code and message when relevant. `cli.ExitCode.of` maps them to exit codes (plan D23).
* Logging only through the project's `WrapperLog` facade. Secrets are masked by the config layer, so
  nothing above it can leak them.
* Naming: `*Config` for typed configuration views, `*Probe`, `*StopStrategy`, `Windows*` for Windows
  platform implementations, `Jna*` for the thin facades that call JNA directly, `Fake*` for test
  doubles, `*Test` for TestNG classes.
* Javadoc on public types explains *why* and the contract; code comments explain non-obvious *why*,
  not *what*.
* Formatting: 4-space indent, ~120-column lines, UTF-8, LF line endings, one blank line between
  members. An `.editorconfig` enforces the basics (Phase 0).
* Dependencies: keep them minimal and justified in the plan's decisions log. Current allowed runtime
  deps for the wrapper: `net.java.dev.jna:jna`, `net.java.dev.jna:jna-platform` and the project's
  own `nyajsw-agent` (shared protocol). The agent has none.

### 4.5 Testing rules

* Unit tests must run on any OS without admin rights. Everything that touches Win32 is behind an
  interface (`platform.*`) and is tested with Mockito against **our own thin facade interfaces**
  (e.g. `ScmApi`, `ProcessApi`), not against JNA classes directly. Hand-written JNA structures get layout tests
  (`size()`, field order) that run everywhere.
* Real-SCM tests live in TestNG group `windows-integration`. They install a throwaway service named
  `nyajsw-test-<random>` and must remove it in `@AfterMethod(alwaysRun = true)` even when the test
  fails.
* Time-dependent logic takes a `Clock`/ticker so tests are deterministic; no `Thread.sleep` in unit
  tests unless unavoidable and bounded.
* Config fixtures live in `nyajsw-wrapper/src/test/resources/conf/`. Real-world `wrapper.conf`
  files from existing deployments (anonymised) are the most valuable fixtures we have; add them.
* Every configuration key, probe type, stop strategy and lifecycle transition has at least one test.

### 4.6 Configuration compatibility rules

* Never rename or remove a YAJSW key. Accept well-known aliases (e.g. `DELAYED_AUTO_START` and
  `DELAY_START`).
* **All keys are declared once** in the key registry (`config.ConfigKeys`): name or pattern, type,
  default, origin (`YAJSW` or `NYAJSW`), support status, one-line description. Parsing, validation,
  warnings and `docs/COMPATIBILITY.md` are all derived from it. A test fails if the generated
  document is stale.
* Unknown keys produce a warning, never a failure. Known-but-unsupported keys produce a warning that
  names the key and its status. The only keys that fail validation are those registered as
  `UNSUPPORTED_FATAL`, where ignoring them would silently change behaviour (e.g.
  `wrapper.app.account`).
* nyajsw extensions also live under `wrapper.*` (single namespace for users) but are tagged
  `NYAJSW` in the registry and listed as extensions in the matrix.
* Precedence (highest first): system properties of the wrapper JVM → configuration file (with its
  `#include`s) → built-in defaults.

### 4.7 Security

* Mask every `*.password` value in logs, dumps, error messages and `toString()`.
* The agent channel is loopback-only and token-authenticated; the token reaches the child JVM through
  the `NYAJSW_AGENT_TOKEN` environment variable, never on the command line, and is never written to
  the log. Command lines are logged through the masking form of `JavaCommandBuilder`.
* Never pass configuration values through a shell. Build argument arrays for `ProcessBuilder`.
* Service install/uninstall need elevation: detect `ERROR_ACCESS_DENIED` and say "run as
  Administrator" instead of dumping a stack trace.

### 4.8 Documentation

* `README.md`: user-facing quick start (Phase 0/8).
* `docs/IMPLEMENTATION_PLAN.md`: status checkboxes, decisions log, open questions. Updated in the
  same commit as the work.
* `docs/COMPATIBILITY.md`: generated; never hand-edited.
* Guides (`docs/probes.md`, `docs/migration-from-yajsw.md`, …) when the corresponding feature lands.

## 5. Definition of done (per change)

- [ ] Code + TestNG tests, `mvn -q verify` green on the developer machine.
- [ ] Review subagent run; blocking/major findings fixed; deferred minors recorded in the plan's
      "Deferred review findings" list.
- [ ] No duplicated logic introduced; new behaviour added through the existing extension points.
- [ ] Key registry and generated compatibility doc updated if configuration changed.
- [ ] Plan checkbox ticked, decisions/matrix/"Last updated" adjusted.
- [ ] Single focused commit with the message format above, pushed to the designated branch.

## 6. Non-goals (do not drift into these without a plan change)

* Reimplementing YAJSW features that are out of scope: system tray, Groovy scripting, JMX console,
  web/remote console, init.d scripts. They are warned about and ignored (see matrix).
* Rewriting the wrapped application or requiring code changes in it. The agent is optional.
* Supporting JVMs older than Java 8 for the wrapped application.
* Third-party CLI frameworks or dependency-injection containers. Plain Java is enough here.

## 7. Glossary

| Term | Meaning |
|---|---|
| SCM | Windows Service Control Manager (`services.exe`), driven through `advapi32` |
| wrapper (process) | The nyajsw JVM started by the SCM (or by the user in console mode) |
| application (process) | The child JVM running the user's program, started by the wrapper |
| agent | `nyajsw-agent.jar`, a `-javaagent` loaded into the application JVM for heartbeat and graceful stop |
| probe | A readiness or liveness check (`process`, `tcp`, `http`, `log`, `file`, `command`, `agent`) |
| stop strategy | An ordered attempt to stop the application gracefully before terminating it |
| key registry | `ConfigKeys`: the single declaration of all configuration keys |
