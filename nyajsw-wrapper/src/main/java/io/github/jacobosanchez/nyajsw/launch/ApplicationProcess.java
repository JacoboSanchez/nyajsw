package io.github.jacobosanchez.nyajsw.launch;

import io.github.jacobosanchez.nyajsw.config.JavaConfig;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.config.SecretMasker;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;
import io.github.jacobosanchez.nyajsw.platform.HostOs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One run of the application (plan P2.4): started from a {@link JavaCommand} in the configured
 * working directory with the configured environment, its standard output and error copied into the
 * wrapper log by {@link OutputPump}s under the source {@code jvm N}, decoded with the charsets the
 * command records and with the configuration's secrets masked, and its standard input closed, so an application that reads it sees the end of
 * input instead of waiting for a console a service does not have.
 *
 * <p>Environment ({@code wrapper.app.env.<variable>}): a value replaces the inherited variable, which
 * on Windows means every spelling of its name, since Windows names ignore case and Java's map does
 * not; an empty value removes it, like {@code set NAME=} in a Windows command prompt, which is how
 * such a line is usually meant.
 *
 * <p>The Java executable is checked when it has a directory; a bare name such as {@code java} is
 * looked up by the operating system on the wrapper's own {@code PATH}, not on {@code
 * wrapper.app.env.PATH}.
 */
public final class ApplicationProcess {

    /** How long the pumps may take to drain after the process ended; a grandchild may hold the pipes. */
    static final Duration DRAIN = Duration.ofSeconds(2);

    private final Process process;
    private final String name;
    private final List<Thread> pumps;
    private final WrapperLog log;
    /** Completes once, with the exit code, after the output is drained and the exit logged. */
    private final CompletableFuture<Integer> exit;

    private ApplicationProcess(Process process, String name, List<Thread> pumps, WrapperLog log) {
        this.process = process;
        this.name = name;
        this.pumps = pumps;
        this.log = log;
        // Draining may block for DRAIN, so it runs on its own thread, not on the JDK's process reaper.
        this.exit = process.onExit().thenApplyAsync(this::ended,
                task -> Thread.ofPlatform().daemon().name("nyajsw-" + name.replace(' ', '-') + "-exit").start(task));
    }

    /**
     * Starts the application.
     *
     * @param command    the command line, from {@link JavaCommandBuilder}
     * @param java       the configured application: working directory and environment
     * @param invocation the number of this run, 1 for the first; lines are logged as {@code jvm N}
     * @param secrets    hides secrets in messages
     * @param log        the wrapper log
     * @throws LaunchException when the Java executable or the working directory does not exist, or
     *                         the process cannot be created; the message is masked and carries no
     *                         cause, whose own message would not be
     */
    public static ApplicationProcess start(JavaCommand command, JavaConfig java, int invocation,
            SecretMasker secrets, WrapperLog log) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(java, "java");
        Objects.requireNonNull(secrets, "secrets");
        Objects.requireNonNull(log, "log");
        if (invocation < 1) {
            throw new IllegalArgumentException("invocations count from 1: " + invocation);
        }
        boolean windows = HostOs.isWindows();
        checkExecutable(command.executable(), windows, secrets);
        if (!Files.isDirectory(java.workingDir())) {
            throw new LaunchException("the working directory " + secrets.maskText(java.workingDir().toString())
                    + " does not exist");
        }
        String name = "jvm " + invocation;
        log.status("launching " + name + ": " + command.loggable());
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command.arguments()).directory(java.workingDir().toFile());
            applyEnvironment(builder.environment(), java.environment(), windows);
            process = builder.start();
        } catch (IOException | IllegalArgumentException e) {
            // An IllegalArgumentException names a value the JDK rejected (a NUL in it), which may be a secret.
            throw new LaunchException("cannot start " + name + ": " + secrets.maskText(String.valueOf(e.getMessage())));
        }
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            log.debug(() -> "cannot close the standard input of " + name + ": " + e.getMessage());
        }
        WrapperLog output = log.forSource(name);
        List<Thread> pumps = List.of(
                pump(new OutputPump(process.getInputStream(), command.stdoutCharset(), output, LogLevel.INFO,
                        secrets), name, "stdout"),
                pump(new OutputPump(process.getErrorStream(), command.stderrCharset(), output, LogLevel.INFO,
                        secrets), name, "stderr"));
        log.status(name + " started, pid " + process.pid());
        return new ApplicationProcess(process, name, pumps, log);
    }

    private static Thread pump(OutputPump pump, String name, String stream) {
        return Thread.ofPlatform().daemon().name("nyajsw-" + name.replace(' ', '-') + "-" + stream).start(pump);
    }

    /**
     * Applies {@code wrapper.app.env.<variable>} to the environment the process inherits: a value
     * replaces the variable, an empty value removes it; with Windows rules every spelling of the name
     * is replaced or removed.
     */
    static void applyEnvironment(Map<String, String> environment, Map<String, String> configured, boolean windows) {
        configured.forEach((variable, value) -> {
            if (windows) {
                environment.keySet().removeIf(existing -> existing.equalsIgnoreCase(variable));
            } else {
                environment.remove(variable);
            }
            if (!value.isEmpty()) {
                environment.put(variable, value);
            }
        });
    }

    /**
     * A command with a directory must name an existing file ({@code .exe} may be left out under
     * Windows rules); a bare name is left to the operating system's {@code PATH} lookup.
     */
    static void checkExecutable(String executable, boolean windows, SecretMasker secrets) {
        if (executable.indexOf('/') < 0 && executable.indexOf('\\') < 0) {
            return;
        }
        try {
            if (Files.isRegularFile(Path.of(executable))
                    || (windows && Files.isRegularFile(Path.of(executable + ".exe")))) {
                return;
            }
        } catch (InvalidPathException e) {
            // reported below
        }
        throw new LaunchException("the Java executable " + secrets.maskText(executable) + " does not exist");
    }

    /** The operating system's process id. */
    public long pid() {
        return process.pid();
    }

    /** {@code jvm N}. */
    public String name() {
        return name;
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Waits for the process to end and its output to be logged.
     *
     * @return the exit code
     */
    public int waitFor() throws InterruptedException {
        try {
            return exit.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("waiting for " + name + " failed", e.getCause());
        }
    }

    /**
     * Waits at most {@code timeout} for the process to end and its output to be logged.
     *
     * @return the exit code, or empty when it is still running
     */
    public Optional<Integer> waitFor(Duration timeout) throws InterruptedException {
        try {
            return Optional.of(exit.get(timeout.toNanos(), TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (ExecutionException e) {
            throw new IllegalStateException("waiting for " + name + " failed", e.getCause());
        }
    }

    /** Completes with the exit code once the process has ended and its output is logged. */
    public CompletableFuture<Integer> onExit() {
        return exit.copy();
    }

    /**
     * Kills the process and everything it started, at once. On Windows {@code Process.destroy()} is
     * already a hard kill and leaves grandchildren, such as a JVM started by a batch file, running;
     * this kills the process first, so it cannot start more, then its descendants, looking again
     * below each one until nothing new turns up. A process started in the moment between finding and
     * killing its parent can still escape; the job object of P6.4 closes that gap on Windows.
     * Graceful stops are the stop strategies' job (P3.4, P7).
     */
    public void terminateTree() {
        Deque<ProcessHandle> pending = new ArrayDeque<>(process.toHandle().descendants().toList());
        process.destroyForcibly();
        Set<Long> seen = new HashSet<>();
        int descendants = 0;
        int failed = 0;
        while (!pending.isEmpty()) {
            ProcessHandle next = pending.poll();
            if (!seen.add(next.pid())) {
                continue;
            }
            List<ProcessHandle> below = next.descendants().toList();
            descendants++;
            if (next.isAlive() && !next.destroyForcibly()) {
                failed++;
            }
            pending.addAll(below);
        }
        if (failed > 0) {
            log.error(name + " terminated, but " + failed + " of its " + descendants
                    + " descendant process(es) could not be killed");
        } else {
            log.status(name + " terminated with " + descendants + " descendant process(es)");
        }
    }

    private int ended(Process ended) {
        int code = ended.exitValue();
        try {
            drain();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            log.status(name + " exited with code " + code);
        } catch (RuntimeException e) {
            // The exit code matters more than the log line.
        }
        return code;
    }

    private void drain() throws InterruptedException {
        long deadline = System.nanoTime() + DRAIN.toNanos();
        for (Thread pump : pumps) {
            long left = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            pump.join(left);
            if (pump.isAlive()) {
                // The thread stays until the pipe closes; its lines keep the name of this run.
                log.status(pump.getName() + " still reading after " + name + " ended; a process it started"
                        + " holds its output open");
            }
        }
    }
}
