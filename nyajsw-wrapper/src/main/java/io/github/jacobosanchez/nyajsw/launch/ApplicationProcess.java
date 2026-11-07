package io.github.jacobosanchez.nyajsw.launch;

import io.github.jacobosanchez.nyajsw.agent.AgentProtocol;
import io.github.jacobosanchez.nyajsw.agentlink.AgentChannel;
import io.github.jacobosanchez.nyajsw.agentlink.AgentListener;
import io.github.jacobosanchez.nyajsw.config.JavaConfig;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.config.SecretMasker;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;
import io.github.jacobosanchez.nyajsw.platform.HostOs;
import io.github.jacobosanchez.nyajsw.platform.ProcessJob;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 *
 * <p>Given a {@link ProcessJob} (plan P6.4), the process is put in it as soon as it has started, so
 * that it and what it starts from then on end with the job; {@link #terminateTree()} ends the job
 * first. A process that cannot be put in it runs without, with an error logged.
 */
public final class ApplicationProcess implements RunningApplication {

    /** How long the pumps may take to drain after the process ended; a grandchild may hold the pipes. */
    static final Duration DRAIN = Duration.ofSeconds(2);

    private final Process process;
    private final String name;
    private final List<Thread> pumps;
    private final WrapperLog log;
    /** The job the process is in, if any. */
    private final Optional<ProcessJob> job;
    /** Completes once, with the exit code, after the output is drained and the exit logged. */
    private final CompletableFuture<Integer> exit;
    private final OutputLines output;
    /** The listener of its agent, if it has one; closed when the process has ended. */
    private final Optional<AgentListener> agent;

    private ApplicationProcess(Process process, String name, List<Thread> pumps, WrapperLog log,
            Optional<ProcessJob> job, OutputLines output, Optional<AgentListener> agent) {
        this.process = process;
        this.name = name;
        this.pumps = pumps;
        this.log = log;
        this.job = job;
        this.output = output;
        this.agent = agent;
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
        return start(command, java, invocation, secrets, log, Optional.empty());
    }

    /**
     * As {@link #start(JavaCommand, JavaConfig, int, SecretMasker, WrapperLog)}, putting the process
     * in {@code job} when one is given.
     */
    public static ApplicationProcess start(JavaCommand command, JavaConfig java, int invocation,
            SecretMasker secrets, WrapperLog log, Optional<ProcessJob> job) {
        return start(command, java, invocation, secrets, log, job, Optional.empty());
    }

    /**
     * As {@link #start(JavaCommand, JavaConfig, int, SecretMasker, WrapperLog, Optional)}, with the
     * listener of the invocation's agent when it has one: its token goes into the environment as
     * {@code NYAJSW_AGENT_TOKEN} (the command line carries the agent's options), and the process
     * closes it once it has ended. Without one the variable is removed, so that the application
     * never sees a token of somebody else's. When the start fails ({@link LaunchException}) the
     * listener stays the caller's to close.
     */
    public static ApplicationProcess start(JavaCommand command, JavaConfig java, int invocation,
            SecretMasker secrets, WrapperLog log, Optional<ProcessJob> job, Optional<AgentListener> agent) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(agent, "agent");
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
        String name = ApplicationLauncher.nameOf(invocation);
        log.status("launching " + name + ": " + command.loggable());
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command.arguments()).directory(java.workingDir().toFile());
            applyEnvironment(builder.environment(), java.environment(), windows);
            applyEnvironment(builder.environment(), Map.of(AgentProtocol.TOKEN_VARIABLE,
                    agent.map(AgentListener::token).orElse("")), windows);
            process = builder.start();
        } catch (IOException | IllegalArgumentException e) {
            // An IllegalArgumentException names a value the JDK rejected (a NUL in it), which may be a secret.
            throw new LaunchException("cannot start " + name + ": " + secrets.maskText(String.valueOf(e.getMessage())));
        }
        Optional<ProcessJob> joined = job.filter(target -> join(target, process, name, log));
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            log.debug(() -> "cannot close the standard input of " + name + ": " + e.getMessage());
        }
        WrapperLog output = log.forSource(name);
        OutputLines lines = new OutputLines();
        List<Thread> pumps = List.of(
                pump(new OutputPump(process.getInputStream(), command.stdoutCharset(), output, LogLevel.INFO,
                        secrets, lines::add), name, "stdout"),
                pump(new OutputPump(process.getErrorStream(), command.stderrCharset(), output, LogLevel.INFO,
                        secrets, lines::add), name, "stderr"));
        log.status(name + " started, pid " + process.pid());
        return new ApplicationProcess(process, name, pumps, log, joined, lines, agent);
    }

    /** Puts {@code process} in {@code job}; whether it is in it. */
    private static boolean join(ProcessJob job, Process process, String name, WrapperLog log) {
        try {
            job.add(process.pid());
            return true;
        } catch (RuntimeException e) {
            if (!process.isAlive()) { // Windows refuses an ended process with "access is denied"
                log.debug(() -> name + " ended before it could be put in the job object");
            } else {
                log.error(name + " runs outside the job object, so it may outlive the wrapper if that is killed: "
                        + e.getMessage());
            }
            return false;
        }
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

    @Override
    public long pid() {
        return process.pid();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
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

    @Override
    public CompletableFuture<Integer> onExit() {
        return exit.copy();
    }

    @Override
    public CompletableFuture<Void> onProcessEnd() {
        return process.onExit().handle((ended, error) -> null);
    }

    @Override
    public OutputLines output() {
        return output;
    }

    @Override
    public Optional<AgentChannel> agent() {
        return agent.flatMap(AgentListener::channel);
    }

    /**
     * Kills the process and everything it started, at once. On Windows {@code Process.destroy()} is
     * already a hard kill and leaves grandchildren, such as a JVM started by a batch file, running;
     * this kills the process first, so it cannot start more, then its descendants ({@link
     * ProcessTrees}). The job object of P6.4 catches what escapes the walk on Windows. Graceful
     * stops are the stop strategies' job (P3.4, P7).
     */
    @Override
    public void terminateTree() {
        // The tree is read before the job ends it, so that it names processes that are still alive.
        List<ProcessHandle> descendants = process.toHandle().descendants().toList();
        job.ifPresent(this::terminateJob);
        process.destroyForcibly();
        ProcessTrees.Killed killed = ProcessTrees.killDescendants(descendants);
        if (killed.failed() > 0) {
            log.error(name + " terminated, but " + killed.failed() + " of its " + killed.descendants()
                    + " descendant process(es) could not be killed");
        } else {
            log.status(name + " terminated with " + killed.descendants() + " descendant process(es)");
        }
    }

    /** Ends everything in the job at once; the walk of the process tree after it catches what was outside. */
    private void terminateJob(ProcessJob target) {
        try {
            target.terminate();
        } catch (RuntimeException e) {
            log.error("cannot end the job object of " + name + ": " + e.getMessage() + "; ending its process tree");
        }
    }

    private int ended(Process ended) {
        int code = ended.exitValue();
        agent.ifPresent(AgentListener::close);
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
