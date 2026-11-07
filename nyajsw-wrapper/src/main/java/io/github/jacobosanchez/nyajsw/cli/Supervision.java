package io.github.jacobosanchez.nyajsw.cli;

import io.github.jacobosanchez.nyajsw.config.ConfigException;
import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ConfigWarning;
import io.github.jacobosanchez.nyajsw.config.KeySpec;
import io.github.jacobosanchez.nyajsw.config.ProbeConfig;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.launch.ApplicationProcess;
import io.github.jacobosanchez.nyajsw.launch.ClasspathExpander;
import io.github.jacobosanchez.nyajsw.launch.JavaCommand;
import io.github.jacobosanchez.nyajsw.launch.JavaCommandBuilder;
import io.github.jacobosanchez.nyajsw.launch.RunningApplication;
import io.github.jacobosanchez.nyajsw.lifecycle.ExecutorScheduler;
import io.github.jacobosanchez.nyajsw.lifecycle.Liveness;
import io.github.jacobosanchez.nyajsw.lifecycle.PidFiles;
import io.github.jacobosanchez.nyajsw.lifecycle.State;
import io.github.jacobosanchez.nyajsw.lifecycle.StopChain;
import io.github.jacobosanchez.nyajsw.lifecycle.StopStrategyRegistry;
import io.github.jacobosanchez.nyajsw.lifecycle.Supervisor;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;
import io.github.jacobosanchez.nyajsw.platform.ProcessJob;
import io.github.jacobosanchez.nyajsw.platform.ProcessTools;
import io.github.jacobosanchez.nyajsw.probe.ProbeRegistry;
import io.github.jacobosanchez.nyajsw.probe.ProbeRunner;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The application under a {@link Supervisor}, as console mode and service mode both run it: its
 * restart policy, readiness and liveness probes, stop strategies and pid files, each invocation launched with a
 * freshly expanded classpath. It runs once.
 *
 * <p>When {@code wrapper.control} ends the application with the wrapper ({@code TIGHT}, {@code
 * APPLICATION}), every invocation goes in one kill-on-close {@link ProcessJob} where the platform has
 * one (plan P6.4), closed when supervision ends: then not even a wrapper that is killed leaves the
 * application behind, {@code terminate} ends what it started too, and so does the end of
 * supervision, also after the application ended by itself. Under {@code LOOSE} and {@code
 * WRAPPER} the application may outlive the wrapper, so there is no job.
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
    private final ProcessTools processes;
    /** The job of this run, from {@link #run()} on; read by the launches, which run on the same thread. */
    private Optional<ProcessJob> job = Optional.empty();
    private final Supervisor supervisor;
    private final AtomicBoolean started = new AtomicBoolean();

    /**
     * @param wrapperOptions the JVM options the wrapper adds to every invocation ({@code -Xrs} in
     *                       service mode)
     * @param listener       told about every change of the supervisor's state
     * @param processes      the platform's help with processes ({@link ProcessTools#NONE} for none)
     * @throws ConfigException when a readiness or liveness probe type is not known or cannot be built
     */
    Supervision(WrapperConfig config, WrapperLog log, JavaCommandBuilder builder, ClasspathExpander expander,
            List<String> wrapperOptions, Supervisor.StateListener listener, ProcessTools processes) {
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.expander = Objects.requireNonNull(expander, "expander");
        this.wrapperOptions = List.copyOf(wrapperOptions);
        this.stops = StopStrategyRegistry.defaults().chain(config.lifecycle(), config.secrets());
        this.pidFiles = new PidFiles(config.lifecycle(), config.secrets(), log);
        this.processes = Objects.requireNonNull(processes, "processes");
        ProbeConfig probes = config.probes();
        ProbeRegistry registry = ProbeRegistry.defaults();
        ProbeRunner readiness = new ProbeRunner(probes(registry, ConfigKeys.READINESS_TYPE, probes.readinessTypes()),
                probes.readinessInterval());
        ProbeRunner livenessProbes = new ProbeRunner(probes(registry, ConfigKeys.LIVENESS_TYPE, probes.livenessTypes()),
                probes.livenessInterval());
        Liveness liveness = new Liveness(livenessProbes, probes.livenessFailures(), probes.livenessAction());
        this.supervisor = new Supervisor(config.lifecycle(), pidFiles.tracking(this::launch), readiness, liveness,
                probeExecutor, stops, scheduler, log, listener);
    }

    /** The probes {@code key} lists; one the registry cannot build is a configuration error, shown masked. */
    private List<ProbeRegistry.NamedProbe> probes(ProbeRegistry registry, KeySpec key, List<String> types) {
        try {
            return registry.create(types, config.probes());
        } catch (IllegalArgumentException e) {
            throw new ConfigException(config.secrets().maskText(key.name() + ": " + e.getMessage()));
        }
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
            if (config.lifecycle().control().applicationEndsWithWrapper()) {
                job = createJob(processes, log);
            }
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
        job.ifPresent(ProcessJob::close);
    }

    /** The job of the application's processes, if the platform can make one; an error is logged, not fatal. */
    private static Optional<ProcessJob> createJob(ProcessTools processes, WrapperLog log) {
        try {
            return processes.createKillOnCloseJob();
        } catch (RuntimeException e) {
            log.error(e.getMessage() + "; the application runs without it, so it may outlive the wrapper if that is"
                    + " killed");
            return Optional.empty();
        }
    }

    /** One invocation: the classpath is expanded again, so jars added since the last start are picked up. */
    private RunningApplication launch(int invocation) {
        log.applicationStarting(invocation);
        ClasspathExpander.Expanded classpath = expander.expand(config.java().classpath(), config.java().workingDir(),
                config.secrets());
        warn(classpath.warnings());
        JavaCommand command = builder.build(config.java(), classpath.entries(), wrapperOptions, config.secrets());
        warn(command.warnings());
        return ApplicationProcess.start(command, config.java(), invocation, config.secrets(), log, job);
    }

    private void warn(List<String> warnings) {
        for (String warning : warnings) {
            log.status("warning: " + warning);
        }
    }
}
