# Running the application as a Windows service

nyajsw installs a Windows service that runs the application of a `wrapper.conf`, the same file that
console mode (`-c`) runs. The service itself is the nyajsw wrapper on Java 21; the application may
run on another JVM (`wrapper.java.command`, given with its directory, see *Application JVM*).

Below, `nyajsw` stands for the wrapper's command line. Until the distribution ships its scripts,
that is `java -cp <the nyajsw and JNA jars> io.github.jacobosanchez.nyajsw.cli.Main`, run with Java
21 or later.

## Commands

Run these from an Administrator prompt, except `-q`:

| Command | What it does |
|---|---|
| `nyajsw -i wrapper.conf` | Installs the service named by `wrapper.ntservice.name` |
| `nyajsw -t wrapper.conf` | Starts it and waits until it runs |
| `nyajsw -p wrapper.conf` | Stops it and waits until it has stopped |
| `nyajsw -q wrapper.conf` | Shows its state (no Administrator rights needed) |
| `nyajsw -r wrapper.conf` | Removes it |

`-r` does not stop the service. Stop it with `-p` first: Windows deletes a service only once it
has stopped and no program, such as the Services console, has it open.

The service runs `javaw.exe` of the JVM that ran `-i` (or `wrapper.ntservice.java.command`, a Java
21 or later), with the classpath of the installing JVM, and `--service <wrapper.conf>`. The
Service Control Manager runs that command line; running it by hand fails with "the process was not
started as a service".

### What needs a reinstall

`-i` copies some settings into the service's registration. After changing any of them, remove
the service (`-p`, then `-r`) and install it again:

- the `wrapper.ntservice.*` keys: name, display name, description, start type, dependencies,
  account and password, `wrapper.ntservice.additional.<n>` and `wrapper.ntservice.java.command`.
  `wrapper.ntservice.reduce_signals` is the exception: it is read at every start;
- `wrapper.tmp.path`;
- `wrapper.shutdown.timeout` and `wrapper.jvm_exit.timeout`, which set how long Windows waits for
  the service at system shutdown;
- the location of the configuration file, of the nyajsw jars and of the JDK that ran `-i`.

To rename the service, remove it with the configuration that still has the old
`wrapper.ntservice.name`. Otherwise `-r` looks for the new name, and the old service stays.

Everything else is read from `wrapper.conf` (and its includes) at every start.

## When the service starts and stops

- The service shows as *starting* until the application is ready for the first time, and *running*
  from then on. If the application restarts later, the service keeps showing as running.
- While the service is starting, Windows refuses to stop it (error 1061). A long
  `wrapper.startup.delay` therefore cannot be cut short.
- A stop asks the application to end through `wrapper.stop.strategy`, by default `agent,terminate`:
  the nyajsw agent, which the wrapper loads into the application's JVM unless
  `wrapper.agent.enabled=false`, ends it with `System.exit(0)`, so its shutdown hooks run and the
  service stops with exit code 0. If the application does not end within the stop budget,
  `wrapper.shutdown.timeout` (30 s by default) plus `wrapper.jvm_exit.timeout`, or there is no
  agent to ask, the wrapper waits out the budget and then ends the application's process tree.
  Without the agent nothing asks the application to end, since a service receives no Ctrl+C;
  the wrapper log warns about that at every start.
- If the application cannot be kept running, or ends by itself with an exit code other than 0, the
  service stops with error 1066. `sc query <name>` shows the code as `SERVICE_EXIT_CODE`: it is
  the code console mode would have exited with. A configuration that cannot be read gives 2.
- At system shutdown the service is asked to stop early, as a pre-shutdown notification. Windows
  then gives it the stop budget plus 20 s of margin, as set at install.
- With `wrapper.ntservice.reduce_signals=true` (the default), the application JVM is started with
  `-Xrs`, so that a user logging off does not end it.

### Processes the application starts

With `wrapper.control=TIGHT` (the default) or `APPLICATION`, the application runs in a Windows job
object. Everything it starts runs in the job too. The job ends with the wrapper's supervision, so
nothing the application started is left running:

- when the service stops;
- when the application ends by itself and the service stops with it, so a helper it started to
  restart the service (`net stop` then `net start`) is ended as well;
- when the wrapper itself is killed.

To let processes outlive the wrapper, use `wrapper.control=LOOSE` or `WRAPPER` (the application is
then not stopped with the service either), or start the helper outside the application, for
example through the Task Scheduler. A process the application starts within the first moment,
before the wrapper has put it in the job, stays outside it. When `wrapper.java.command` is a
script, the JVM the script starts can be one of those.

## Accounts

The service runs as `LocalSystem` unless `wrapper.ntservice.account` names another account:

- `NT AUTHORITY\LocalService` or `NT AUTHORITY\NetworkService`, Windows' built-in service
  accounts;
- `NT SERVICE\<service name>`, a virtual account of the service's own;
- `.\name`, a local account, or `DOMAIN\name`, a domain account, with its password in
  `wrapper.ntservice.password`. `-i` grants such an account the right to log on as a service;
  the others need no such grant.

The password is stored by Windows at install and masked in every message nyajsw writes. The rest
of the service's command line, including `wrapper.ntservice.additional.<n>`, can be read by every
local user (`sc qc <name>`): put no secret there.

The account must be able to:

- read the configuration file and everything it includes, the nyajsw jars, the JDKs of the wrapper
  and of the application, and the application's own files;
- write wherever `wrapper.logfile`, `wrapper.pidfile` and `wrapper.java.pidfile` point (by default
  the log file is `wrapper.log` beside the configuration), and whatever the application writes in
  its working directory;
- write `wrapper.tmp.path` (see *Native library*).

When the log file or a pid file cannot be opened because access is denied, the message names the
account the wrapper runs as and the key that chose the place.

**Protect the installation.** Whoever can change the configuration, its includes, the nyajsw jars
or the JDKs decides what the service runs, and the service runs as its account, `LocalSystem` by
default. These files must be writable only by Administrators (and, where it writes logs, the
service account). A folder under `C:\Program Files` is protected that way. A folder created
directly under `C:\` is not by default: every authenticated user may change files there until
that inherited permission is removed.

## Logs

- **The wrapper log** is the file `wrapper.logfile` names: `wrapper.log` beside the configuration
  by default. A service has no console, so the `wrapper.console.*` keys have no effect.
- **The bootstrap log** is `nyajsw-bootstrap-<service name>.log`. It holds what goes wrong before
  the wrapper log is open (a configuration that cannot be read) and the problems of the wrapper log
  itself (a file that cannot be opened, a disk that is full). It is created only when there is
  something to write, so a service that starts cleanly has none.
  - It is written beside the configuration file. When the account may not create files there, it
    goes to the account's temporary directory instead. For `LocalSystem` that is
    `C:\Windows\Temp`; for other accounts, their profile's `AppData\Local\Temp`.
  - When the wrapper cannot even connect to the Service Control Manager, it does not know the
    service's name yet: the file is then named after the configuration file
    (`nyajsw-bootstrap-wrapper.log` for `wrapper.conf`). Windows typically reports such a service
    as not responding (error 1053).

## Application JVM

`wrapper.java.command` chooses the JVM that runs the application. Give it with its directory, such
as `C:/java/jdk8/bin/java.exe`. Windows looks up a bare `java` in the wrapper's own JDK first,
before `PATH`, so the application would run on the wrapper's Java 21. Configurations migrated from
YAJSW often have that default.

## Native library (JNA)

nyajsw calls Windows through JNA 5.19.1. JNA unpacks its native library, `jnidispatch.dll`, into
a temporary directory when the wrapper starts:

- That directory is `wrapper.tmp.path`, which `-i` writes into the service's command line as
  `-Djna.tmpdir=...`. Without `wrapper.tmp.path`, it is a `jna-<number>` folder in the account's
  temporary directory.
- If that directory is not writable, or antivirus software blocks DLLs there, the service cannot
  start, and Windows reports it as not responding. The reason is in the bootstrap log named after
  the configuration file (see *Logs*).
- **Fallback:** take `jnidispatch.dll` from the JNA jar (also inside the nyajsw jar of the
  distribution), at `com/sun/jna/win32-x86-64/jnidispatch.dll`, or `win32-aarch64` on ARM64.
  Put it in a directory of its own that the account can read. Then add two options, with the
  next free `wrapper.ntservice.additional.<n>` numbers, and reinstall:

  ```
  wrapper.ntservice.additional.1=-Djna.boot.library.path=C:/nyajsw/native
  wrapper.ntservice.additional.2=-Djna.nounpack=true
  ```
