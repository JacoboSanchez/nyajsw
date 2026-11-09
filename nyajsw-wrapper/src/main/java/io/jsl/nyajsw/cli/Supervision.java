package io.jsl.nyajsw.cli;

import io.jsl.nyajsw.agentlink.AgentJar;
import io.jsl.nyajsw.agentlink.AgentListener;
import io.jsl.nyajsw.agentlink.JavaRuntime;
import io.jsl.nyajsw.config.ConfigException;
import io.jsl.nyajsw.config.ConfigKeys;
import io.jsl.nyajsw.config.ConfigWarning;
import io.jsl.nyajsw.config.KeySpec;
import io.jsl.nyajsw.config.ProbeConfig;
import io.jsl.nyajsw.config.WrapperConfig;
import io.jsl.nyajsw.launch.ApplicationLauncher;
import io.jsl.nyajsw.launch.ApplicationProcess;
import io.jsl.nyajsw.launch.ClasspathExpander;
import io.jsl.nyajsw.launch.JavaCommand;
import io.jsl.nyajsw.launch.JavaCommandBuilder;
import io.jsl.nyajsw.launch.RunningApplication;
import io.jsl.nyajsw.lifecycle.AgentStopStrategy;
import io.jsl.nyajsw.lifecycle.ExecutorScheduler;
import io.jsl.nyajsw.lifecycle.Liveness;
import io.jsl.nyajsw.lifecycle.PidFiles;
import io.jsl.nyajsw.lifecycle.State;
import io.jsl.nyajsw.lifecycle.StopChain;
import io.jsl.nyajsw.lifecycle.StopStrategyRegistry;
import io.jsl.nyajsw.lifecycle.Supervisor;
import io.jsl.nyajsw.log.WrapperLog;
import io.jsl.nyajsw.platform.ProcessJob;
import io.jsl.nyajsw.platform.ProcessTools;
import io.jsl.nyajsw.probe.AgentProbe;
import io.jsl.nyajsw.probe.ProbeRegistry;
import io.jsl.nyajsw.probe.ProbeRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
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
    /** The agent's jar when the agent is injected (plan P7.3); without one, none is. */
    private final Optional<Path> agentJar;
    private final SecureRandom random = new SecureRandom();
    /** Set once the application's runtime turned out to lack {@code java.instrument}, which is warned about once. */
    private boolean noInstrumentation;
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
                probes.readinessInterval(), config.secrets()::maskText);
        ProbeRunner livenessProbes = new ProbeRunner(probes(registry, ConfigKeys.LIVENESS_TYPE, probes.livenessTypes()),
                probes.livenessInterval(), config.secrets()::maskText);
        Liveness liveness = new Liveness(livenessProbes, probes.livenessFailures(), probes.livenessAction());
        this.supervisor = new Supervisor(config.lifecycle(), pidFiles.tracking(this::launch), readiness, liveness,
                probeExecutor, stops, scheduler, log, listener);
        this.agentJar = agentJar(config);
    }

    /**
     * The agent's jar, when the agent is in effect and the application is a JVM: {@code
     * wrapper.image} runs an executable that may be no JVM at all.
     */
    private static Optional<Path> agentJar(WrapperConfig config) {
        return agentWanted(config) ? AgentJar.locate() : Optional.empty();
    }

    /** Whether the agent is to be injected: the predicate the derived defaults follow too. */
    private static boolean agentWanted(WrapperConfig config) {
        return config.probes().agentInEffect();
    }

    /** What is configured to use the agent: probe types and stop strategies named {@code agent}. */
    private List<String> agentUsers() {
        List<String> users = new ArrayList<>();
        if (named(config.probes().readinessTypes(), AgentProbe.TYPE)) {
            users.add(ConfigKeys.READINESS_TYPE.name());
        }
        if (named(config.probes().livenessTypes(), AgentProbe.TYPE)) {
            users.add(ConfigKeys.LIVENESS_TYPE.name());
        }
        if (stops.graceful().stream().anyMatch(step -> step.name().equals(AgentStopStrategy.NAME))) {
            users.add(ConfigKeys.STOP_STRATEGY.name());
        }
        return users;
    }

    private static boolean named(List<String> names, String name) {
        return names.stream().anyMatch(listed -> listed.strip().equalsIgnoreCase(name));
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
            if (agentWanted(config) && agentJar.isEmpty()) {
                log.status("warning: the agent's jar (" + AgentJar.FILE_NAME + ") is not found beside the wrapper"
                        + " (or its path holds '='); the application runs without the agent");
            }
            List<String> users = agentUsers();
            // Only where the agent is off do the lists name it because the file says so; the derived
            // defaults name it only while it is in effect, and a missing jar is warned about above.
            if (!agentWanted(config) && !users.isEmpty()) {
                log.status("warning: " + String.join(", ", users) + " names the agent, but the application runs"
                        + " without it (" + ConfigKeys.AGENT_ENABLED.name() + ", " + ConfigKeys.IMAGE.name()
                        + "): agent probes pass without checking and the agent stop does not apply");
            }
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
        // Before the job and the log: a stop command still running is ended and its outcome logged.
        stops.close();
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
        JavaCommand plain = builder.build(config.java(), classpath.entries(), wrapperOptions, config.secrets());
        warn(plain.warnings());
        Optional<AgentListener> agent = agentFor(plain, ApplicationLauncher.nameOf(invocation));
        try {
            JavaCommand command = plain;
            if (agent.isPresent()) {
                List<String> options = new ArrayList<>(wrapperOptions);
                options.addAll(agent.get().jvmOptions(agentJar.orElseThrow()));
                command = builder.build(config.java(), classpath.entries(), options, config.secrets());
            }
            return ApplicationProcess.start(command, config.java(), invocation, config.secrets(), log, job, agent);
        } catch (RuntimeException e) {
            agent.ifPresent(AgentListener::close);
            throw e;
        }
    }

    /**
     * A listener for the invocation's agent, when there is an agent and the runtime of {@code
     * command} can load it; one that cannot be opened is logged.
     */
    private Optional<AgentListener> agentFor(JavaCommand command, String name) {
        if (agentJar.isEmpty() || noInstrumentation) {
            return Optional.empty();
        }
        if (JavaRuntime.lacksInstrumentation(command.executable())) {
            noInstrumentation = true;
            log.status("warning: the application's Java runtime has no java.instrument module, which the agent"
                    + " needs; the application runs without it (set " + ConfigKeys.AGENT_ENABLED.name()
                    + "=false to say so)");
            return Optional.empty();
        }
        try {
            return Optional.of(AgentListener.open(name, log, random));
        } catch (IOException e) {
            log.error("cannot listen for the agent of " + name + " (" + e.getMessage() + "); it runs without it");
            return Optional.empty();
        }
    }

    private void warn(List<String> warnings) {
        for (String warning : warnings) {
            log.status("warning: " + warning);
        }
    }
}
