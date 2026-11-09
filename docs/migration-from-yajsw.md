# Moving a service from YAJSW to nyajsw

nyajsw reads the `wrapper.conf` that YAJSW runs, so a service moves without rewriting its
configuration. The service itself cannot be taken over, though: Windows stores the command line
of a service, and YAJSW's names YAJSW's jars and often an older JVM. The move is therefore a
reinstall: remove the service with YAJSW, then install it with nyajsw from the same file.

This guide covers the move, the settings that do not come along by themselves, how to read
nyajsw's warnings and where nyajsw behaves differently. [windows-service.md](windows-service.md)
describes running a service with nyajsw, [probes.md](probes.md) the readiness and liveness
checks, and [COMPATIBILITY.md](COMPATIBILITY.md) every configuration key with its status.

In the commands below, `nyajsw` stands for `bin\nyajsw.bat` of the nyajsw distribution, and the
named scripts beside it (`installService.bat` and the others) do the same with fewer words.

## Before you start

1. **Java 21 for the wrapper.** nyajsw runs on Java 21 or later. The application keeps the Java it
   has: `wrapper.java.command` chooses it, and any Java 8 or later works. Write that key with the
   Java's directory (`C:/java/jdk8/bin/java.exe`): a bare `java`, YAJSW's default, is found in the
   wrapper's own JDK first, so the application would run on Java 21.
2. **Unpack nyajsw** where only Administrators may change files, such as under
   `C:\Program Files`. The service runs what that folder and the configuration say, under its
   account (`LocalSystem` unless configured otherwise).
3. **Move the configuration out of the YAJSW folder** if it is there (`<yajsw>\conf\wrapper.conf`
   is a common place), with the files it includes, and check that no value points into that
   folder (`wrapper.tmp.path`, `wrapper.logfile`, classpath entries). The new service keeps the
   configuration file's absolute path and writes its logs beside it by default, so deleting the
   YAJSW folder later would break it.
4. **Check the configuration.** From any prompt, without Administrator rights:

   ```
   nyajsw -q C:\app\conf\wrapper.conf
   ```

   It changes nothing: it reads the file as the service will, prints a warning for every key
   nyajsw does not use (see *Reading the warnings*), and then shows the state of the service of
   that name, which is still YAJSW's. If it ends with an error instead (exit code 2), fix that
   first: the service would not start. Some problems show only when the application is started:
   an unknown probe type, a `wrapper.java.command` that does not exist, a double quote inside a
   JVM option. And `${env:NAME}` is resolved with your environment, not the service account's.
   Step 2 of *The move* is a chance to try the application in the console first.
5. **Write down what the old registration has that the file does not** (next section), with
   `sc qc <name>`, `sc qfailure <name>` and the Services console.

## Settings that do not come along

`-i` builds the new registration from `wrapper.conf` only. Settings made on the old one in other
ways are lost when it is removed:

| Set on the old registration | Put it here before `-i` |
|---|---|
| JVM options added to its command line (`sc config … binPath=`, such as `-D` flags) | `wrapper.ntservice.additional.<n>`, one option per key; `-Djna.tmpdir` becomes `wrapper.tmp.path` |
| The account and password entered in the Services console | `wrapper.ntservice.account` and `wrapper.ntservice.password` |
| A start type changed in the console or with `sc config … start=` | `wrapper.ntservice.starttype` |
| Dependencies added with `sc config … depend=` | `wrapper.ntservice.dependency.<n>` |
| Recovery actions (`sc failure`, the console's *Recovery* tab) | No key yet: set them again after `-i` (see *Service recovery*) |

If the account's right to log on as a service comes from a domain policy, the policy must still
grant it after the move. `-i` grants the right on the local machine, but a domain policy that
defines the right replaces the local list at its next refresh, and the service then fails with
error 1069, as it would after setting the account in the Services console.

## The move

Open an Administrator prompt. `<name>` is the service's `wrapper.ntservice.name`.

1. **Stop the service with YAJSW** (its `-p`, or its `stopService` script).
2. *Optional:* **try the application in the console** with `nyajsw -c C:\app\conf\wrapper.conf`,
   and stop it with Ctrl+C. It runs under your account, not the service's, but shows the
   problems that only a start reveals.
3. **Remove it with YAJSW** (its `-r`, or its `uninstallService` script).
4. **Wait until Windows has deleted it.** Close the Services console and anything else that has
   the service open; `sc query <name>` then reports error 1060 (the service does not exist).
   Until then the next step fails with error 1072 (`ERROR_SERVICE_MARKED_FOR_DELETE`).
5. **Install it with nyajsw**, from the same file:

   ```
   nyajsw -i C:\app\conf\wrapper.conf
   ```

   The service runs the `javaw.exe` of the Java that ran this command (`NYAJSW_JAVA`, else
   `JAVA_HOME`, else `java` from the `PATH`; see [windows-service.md](windows-service.md)), unless
   `wrapper.ntservice.java.command` names another one. That one must be a Java 21 or later too,
   given with its directory: YAJSW defaults it to the application's Java, and `-i` refuses a bare
   name such as `java` as well as a Java it knows to be older.
6. **Start it** and watch the wrapper log (`wrapper.logfile`; without that key, `wrapper.log`
   beside the configuration file, which is not where YAJSW puts it):

   ```
   nyajsw -t C:\app\conf\wrapper.conf
   ```

7. Set the recovery actions again if the service had any, and delete the YAJSW installation once
   the service runs as it should.

To go back, stop and remove the service with nyajsw (`-p`, then `-r`), wait as in step 4, and
install it with YAJSW again.

## Reading the warnings

Every warning names the file and line of the key, so `include`d files are covered too, and none
stops the wrapper. Most say that a key has no effect:

| Warning | Meaning |
|---|---|
| `unknown key <key>; it has no effect` | Not a key nyajsw knows: often a misspelling, or a key of the Tanuki wrapper that YAJSW does not document |
| `<key> is not supported by nyajsw; it has no effect` | A YAJSW key nyajsw does not implement, such as the tray, the scripts or the JMX console |
| `<key> is ignored by nyajsw` | A YAJSW key that nyajsw does not need, such as `wrapper.java.mainclass` or `wrapper.port` |
| `<key> is not implemented yet in this version of nyajsw; it has no effect` | Planned; [COMPATIBILITY.md](COMPATIBILITY.md) names the phase |

Others say how a value was read: a `${...}` that names nothing known is kept as written (often a
variable that the old setup defined elsewhere), and a file that is not UTF-8 is read as
windows-1252. Keys outside `wrapper.*` are variables for `${...}` and get no warning. A few things
stop the
wrapper instead, because ignoring them would change what the service does:

- `wrapper.app.account` and `wrapper.app.password` (the application under another account),
  `wrapper.config.script.<name>`, `wrapper.condition.script` and `wrapper.windows.cluster.*`, when
  they have a value;
- a Groovy expression or a keystore reference inside `${...}`;
- a value that does not fit its key's type, such as a word where a number of seconds belongs.

The message names the key and its line; `-q` shows these errors as well.

## What behaves differently

### Starting and stopping

- **No bootstrap class in the application.** nyajsw starts the application's main class itself;
  `wrapper.java.mainclass` is ignored. Without `wrapper.java.app.mainclass` or
  `wrapper.java.app.jar`, the first `wrapper.app.parameter.<n>` is the main class, which is what
  files converted from the Tanuki wrapper rely on (`WrapperSimpleApp` and the real class as the
  first parameter). `WrapperStartStopApp` files list more parameters after the start class: the
  count of its arguments, the arguments, then the stop class with its own settings. nyajsw passes
  everything after the first parameter to the start class's `main`, so keep only the start class
  and its arguments. The stop class is never called: a stop ends the JVM through the agent's
  `System.exit(0)`, so whatever must run at a stop belongs in a shutdown hook. A program that
  asks the application to stop, such as YAJSW's `wrapper.stop.conf` program or a `.bat` file, can
  be run first with `wrapper.stop.command` (see the README, "Stopping the application").
- **Running means ready.** The service shows as *running* once the readiness probes pass, by
  default as soon as the process exists (`wrapper.readiness.type=process`).
  `wrapper.ntservice.autoreport.startup=false` has no effect; a probe takes its place.
- **A graceful stop through the agent.** nyajsw loads a small agent into the application's JVM
  (`-javaagent`, plus a `-D` option with its port) unless `wrapper.agent.enabled=false`. A stop
  asks it to end the JVM with `System.exit(0)`, so shutdown hooks run; after
  `wrapper.shutdown.timeout` plus `wrapper.jvm_exit.timeout` the process tree is terminated. Without
  the agent nothing asks the application to end: the wrapper waits that long and then terminates
  it. With `wrapper.shutdown.timeout=0` and `wrapper.jvm_exit.timeout` 0 or not set, the
  application is terminated at once; in the Tanuki format 0 means "wait forever".
- **The heartbeat** is the agent's: `wrapper.ping.interval` and `wrapper.ping.timeout` set it as in
  YAJSW, and the application is terminated and restarted when it stops answering. See
  [probes.md](probes.md) for its exact timing and for other liveness checks.
- **Processes the application starts end with it** under `wrapper.control=TIGHT` (the default) or
  `APPLICATION`; see [windows-service.md](windows-service.md).
- **Restarts.** `wrapper.max_failed_invocations=N` ends supervision after N failed invocations in a
  row. An application that cannot be started at all (a missing Java executable or working
  directory) counts as failed and is retried like any other: with the defaults the wrapper gives up
  after five attempts and about 20 seconds, logging the error each time. An invalid
  `wrapper.on_exit.<code>` value is a configuration error, not a warning.
- **`STOP` as an exit action** keeps the wrapper running without the application only under
  `wrapper.control=LOOSE` or `APPLICATION`; under `TIGHT` and `WRAPPER` it ends the wrapper like
  `SHUTDOWN`.
- **Values are resolved at every start.** YAJSW stores the values of `${...}` when the service is
  installed (`wrapper.save_interpolated`); nyajsw reads the file and resolves them again at each
  start, so an environment variable that changed since the install takes effect once the service
  sees it (services get the system environment of the Service Control Manager, which picks up
  changes at the next restart of Windows).
- **The application's console output is UTF-8.** nyajsw starts the application with
  `-Dstdout.encoding` and `-Dstderr.encoding` (and their `sun.` forms) set to UTF-8, so that its
  output reaches the log intact, unless a `wrapper.java.additional.<n>` sets them.

### The configuration file

- **Only `=` separates a key from its value**, and a comma is part of the value: nyajsw splits no
  value into a list. A backslash before a space or one of `\ , " ' # = : !` is an escape, as in
  YAJSW (so `\\` is one backslash and `\,` a comma); any other backslash is kept, so
  `C:\app\lib` reads as written. Forward slashes avoid the question.
- **One JVM option or argument per key.** `wrapper.java.additional.1=-Xms1g -Xmx2g` passes one
  argument, `-Xms1g -Xmx2g`, which the JVM rejects: use two keys. Quotes around a whole option, or
  around what follows its first `=` (`-Dapp.home="C:/My App"`), are removed; any other double
  quote is an error.
- **Paths are written without quotes.** `wrapper.java.command="C:\Program Files\Java\bin\java.exe"`
  is an error (an invalid path); write `wrapper.java.command=C:/Program Files/Java/bin/java.exe`.
- **Encoding.** A file is read as UTF-8; one that is not valid UTF-8 is read as windows-1252, with
  a warning. `wrapper.conf.encoding` in the file names another encoding. A UTF-16 file is
  rejected unless the wrapper's JVM gets `-Dwrapper.conf.encoding=UTF-16`: through `NYAJSW_OPTS`
  for the commands, and through `wrapper.ntservice.additional.<n>` for the service.
- **Paths.** The wrapper's own relative paths (`wrapper.logfile`, the pid files,
  `wrapper.tmp.path`) are relative to the configuration file's directory; the application's (its
  jar, classpath, library path) to `wrapper.working.dir`, whose default is also the
  configuration file's directory.

### Logging

- Without `wrapper.logfile` the log is `wrapper.log` beside the configuration file. Lines carry
  nyajsw's own time format (`yyyy/MM/dd HH:mm:ss`).
- `wrapper.logfile.maxsize` also rolls the file under `wrapper.logfile.rollmode=JVM`.
- `wrapper.debug.level` takes 0 to 3: 0 writes no debug messages, 1 adds starting and stopping the
  application and the stack trace of an unexpected error, 2 the steps of supervision, 3
  everything. So `wrapper.debug=true` with level 0 writes fewer lines than
  `wrapper.console.loglevel=DEBUG` alone.

## Service recovery

nyajsw restarts the application itself, as `wrapper.on_exit.*` and the restart keys say. The
service stops with an error (1066, with the code in `SERVICE_EXIT_CODE`) only once nyajsw has given
up or the configuration ends it, so recovery actions add a restart *after* that, not a second
restart beside nyajsw's.

Windows applies recovery actions to a service that stops with an error only when the service's
*failure actions flag* is set (`sc failureflag <name> 1`, or *Enable actions for stops with
errors* on the *Recovery* tab); without it, they apply only when the wrapper process itself dies.
nyajsw sets neither the actions nor the flag, and a reinstall drops both, so set them again after
`-i` if you want Windows to try again once nyajsw has given up:

```
sc failure <name> reset= 86400 actions= restart/60000
sc failureflag <name> 1
```
