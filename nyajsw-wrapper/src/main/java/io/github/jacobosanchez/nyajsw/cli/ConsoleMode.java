package io.github.jacobosanchez.nyajsw.cli;

import io.github.jacobosanchez.nyajsw.config.ConfigWarning;
import io.github.jacobosanchez.nyajsw.config.ProbeConfig;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.launch.ApplicationProcess;
import io.github.jacobosanchez.nyajsw.launch.ClasspathExpander;
import io.github.jacobosanchez.nyajsw.launch.JavaCommand;
import io.github.jacobosanchez.nyajsw.launch.JavaCommandBuilder;
import io.github.jacobosanchez.nyajsw.launch.RunningApplication;
import io.github.jacobosanchez.nyajsw.lifecycle.ExecutorScheduler;
import io.github.jacobosanchez.nyajsw.lifecycle.PidFiles;
import io.github.jacobosanchez.nyajsw.lifecycle.State;
import io.github.jacobosanchez.nyajsw.lifecycle.StopChain;
import io.github.jacobosanchez.nyajsw.lifecycle.StopStrategyRegistry;
import io.github.jacobosanchez.nyajsw.lifecycle.Supervisor;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;
import io.github.jacobosanchez.nyajsw.probe.ProbeRegistry;
import io.github.jacobosanchez.nyajsw.probe.ProbeRunner;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Console mode ({@code -c}, plan P3.6): the application runs in the foreground under the {@link
 * Supervisor}, with its restart policy, readiness probes, stop strategies and pid files, and the
 * wrapper returns the application's exit code.
 *
 * <p>{@link #stop()} is what Ctrl+C triggers, through the shutdown hook the entry point registers.
 * The application shares the console and receives the same Ctrl+C, so the default stop chain
 * ({@code terminate} alone) gives it the stop budget to finish its own shutdown before its process
 * tree is terminated.
 */
public final class ConsoleMode {

    /** What {@link #stop()} allows beyond the stop budget for the supervisor's own work. */
    static final Duration STOP_MARGIN = Duration.ofSeconds(10);

    private final WrapperConfig config;
    private final WrapperLog log;
    private final JavaCommandBuilder builder;
    private final ClasspathExpander expander;
    private final StopChain stops;
    private final PidFiles pidFiles;
    private final ExecutorScheduler scheduler = new ExecutorScheduler();
    /** A thread per readiness check, so that one that hangs cannot hold up the next invocation's. */
    private final ExecutorService probeExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Supervisor supervisor;
    private final Object lock = new Object();
    /** Set once {@link #run()} has begun; guarded by {@link #lock}. */
    private boolean running;
    /** Completes with the exit code when {@link #run()} has finished, its last line logged. */
    private final CompletableFuture<Integer> finished = new CompletableFuture<>();

    /**
     * @throws IllegalArgumentException when a readiness probe type is not known
     */
    public ConsoleMode(WrapperConfig config, WrapperLog log, JavaCommandBuilder builder, ClasspathExpander expander) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.expander = Objects.requireNonNull(expander, "expander");
        this.stops = StopStrategyRegistry.defaults().chain(config.lifecycle(), config.secrets());
        this.pidFiles = new PidFiles(config.lifecycle(), config.secrets(), log);
        ProbeConfig probes = config.probes();
        ProbeRunner readiness = new ProbeRunner(ProbeRegistry.defaults().create(probes.readinessTypes(), probes),
                probes.readinessInterval());
        // P6's service host will assemble the same stack; extract it then (deferred review finding).
        this.supervisor = new Supervisor(config.lifecycle(), pidFiles.tracking(this::launch), readiness,
                probeExecutor, stops, scheduler, log, (from, to) -> { });
    }

    /**
     * Logs the configuration's warnings and supervises the application until supervision is over.
     *
     * @return see {@link #exitCode}
     * @throws IllegalStateException when called a second time
     */
    public int run() {
        synchronized (lock) {
            if (running) {
                throw new IllegalStateException("console mode runs once");
            }
            running = true;
        }
        int code = ExitCode.UNEXPECTED_ERROR.code();
        long pid = ProcessHandle.current().pid();
        try {
            for (ConfigWarning warning : config.warnings()) {
                log.status("warning: " + warning);
            }
            warn(stops.warnings());
            pidFiles.wrapperStarted(pid);
            Supervisor.Outcome outcome = supervisor.run();
            code = exitCode(outcome);
            log.status("supervision ended " + outcome.state() + "; exit code " + code + " is "
                    + (outcome.exitCode().isPresent() ? "the application's" : "the wrapper's"));
            return code;
        } finally {
            pidFiles.wrapperEnded(pid);
            probeExecutor.shutdownNow();
            scheduler.close();
            finished.complete(code);
        }
    }

    /**
     * Stops the application through the supervisor's stop chain and waits until {@link #run()} has
     * finished, at most the stop budget plus the supervisor's wait after termination plus {@link
     * #STOP_MARGIN}: a supervisor stuck in a blocking call (a classpath on an unreachable share)
     * must not keep Ctrl+C from ending the wrapper. Safe to call at any time, also before {@link
     * #run()} (which then starts nothing), and more than once.
     *
     * @return the exit code {@link #run()} returns; 0 when it has not begun; {@link
     *         ExitCode#UNEXPECTED_ERROR} when it did not finish in time
     */
    public int stop() {
        supervisor.requestStop();
        synchronized (lock) {
            if (!running) {
                return ExitCode.SUCCESS.code();
            }
        }
        Duration limit = config.lifecycle().stopBudget().plus(Supervisor.AFTER_TERMINATION).plus(STOP_MARGIN);
        try {
            return finished.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.error("supervision did not end within " + limit.toSeconds() + " s of the stop request; ending the"
                    + " wrapper, the application may still run");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // run() completes the future normally, always
        }
        return ExitCode.UNEXPECTED_ERROR.code();
    }

    /**
     * The exit code of console mode: the last invocation's; without one, 0 when supervision stopped
     * as planned (stopped before a start, or the application left running as {@code
     * wrapper.control} allows). Supervision that failed never exits with 0, even when the last
     * invocation did ({@code wrapper.on_exit.0=RESTART} past the restart limit), so that scripts do
     * not take it for success.
     */
    static int exitCode(Supervisor.Outcome outcome) {
        boolean failed = outcome.state() == State.FAILED;
        if (outcome.exitCode().isPresent() && !(failed && outcome.exitCode().getAsInt() == 0)) {
            return outcome.exitCode().getAsInt();
        }
        return failed ? ExitCode.UNEXPECTED_ERROR.code() : ExitCode.SUCCESS.code();
    }

    /** One invocation: the classpath is expanded again, so jars added since the last start are picked up. */
    private RunningApplication launch(int invocation) {
        log.applicationStarting(invocation);
        ClasspathExpander.Expanded classpath = expander.expand(config.java().classpath(), config.java().workingDir(),
                config.secrets());
        warn(classpath.warnings());
        JavaCommand command = builder.build(config.java(), classpath.entries(), config.secrets());
        warn(command.warnings());
        return ApplicationProcess.start(command, config.java(), invocation, config.secrets(), log);
    }

    private void warn(List<String> warnings) {
        for (String warning : warnings) {
            log.status("warning: " + warning);
        }
    }
}
