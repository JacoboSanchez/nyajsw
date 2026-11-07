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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The application under a {@link Supervisor}, as console mode and service mode both run it: its
 * restart policy, readiness probes, stop strategies and pid files, each invocation launched with a
 * freshly expanded classpath. It runs once.
 */
final class Supervision {

    private final WrapperConfig config;
    private final WrapperLog log;
    private final JavaCommandBuilder builder;
    private final ClasspathExpander expander;
    private final List<String> wrapperOptions;
    private final StopChain stops;
    private final PidFiles pidFiles;
    private final ExecutorScheduler scheduler = new ExecutorScheduler();
    /** A thread per readiness check, so that one that hangs cannot hold up the next invocation's. */
    private final ExecutorService probeExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Supervisor supervisor;
    private final AtomicBoolean started = new AtomicBoolean();

    /**
     * @param wrapperOptions the JVM options the wrapper adds to every invocation ({@code -Xrs} in
     *                       service mode)
     * @param listener       told about every change of the supervisor's state
     * @throws IllegalArgumentException when a readiness probe type is not known
     */
    Supervision(WrapperConfig config, WrapperLog log, JavaCommandBuilder builder, ClasspathExpander expander,
            List<String> wrapperOptions, Supervisor.StateListener listener) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.expander = Objects.requireNonNull(expander, "expander");
        this.wrapperOptions = List.copyOf(wrapperOptions);
        this.stops = StopStrategyRegistry.defaults().chain(config.lifecycle(), config.secrets());
        this.pidFiles = new PidFiles(config.lifecycle(), config.secrets(), log);
        ProbeConfig probes = config.probes();
        ProbeRunner readiness = new ProbeRunner(ProbeRegistry.defaults().create(probes.readinessTypes(), probes),
                probes.readinessInterval());
        this.supervisor = new Supervisor(config.lifecycle(), pidFiles.tracking(this::launch), readiness,
                probeExecutor, stops, scheduler, log, listener);
    }

    /**
     * Logs the configuration's warnings and supervises the application until supervision is over;
     * the wrapper's pid file is written for that time.
     */
    Supervisor.Outcome run() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("supervision runs once");
        }
        long pid = ProcessHandle.current().pid();
        try {
            for (ConfigWarning warning : config.warnings()) {
                log.status("warning: " + warning);
            }
            warn(stops.warnings());
            pidFiles.wrapperStarted(pid);
            return supervisor.run();
        } finally {
            pidFiles.wrapperEnded(pid);
            close();
        }
    }

    /** Whether a stop asks the application to end before terminating it ({@code wrapper.stop.strategy}). */
    boolean hasGracefulStop() {
        return !stops.graceful().isEmpty();
    }

    /** Asks the supervisor to stop the application; any thread, any time, also before {@link #run()}. */
    void requestStop() {
        supervisor.requestStop();
    }

    /**
     * The exit code of supervision: the last invocation's; without one, 0 when supervision stopped
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

    private void close() {
        probeExecutor.shutdownNow();
        scheduler.close();
    }

    /** One invocation: the classpath is expanded again, so jars added since the last start are picked up. */
    private RunningApplication launch(int invocation) {
        log.applicationStarting(invocation);
        ClasspathExpander.Expanded classpath = expander.expand(config.java().classpath(), config.java().workingDir(),
                config.secrets());
        warn(classpath.warnings());
        JavaCommand command = builder.build(config.java(), classpath.entries(), wrapperOptions, config.secrets());
        warn(command.warnings());
        return ApplicationProcess.start(command, config.java(), invocation, config.secrets(), log);
    }

    private void warn(List<String> warnings) {
        for (String warning : warnings) {
            log.status("warning: " + warning);
        }
    }
}
