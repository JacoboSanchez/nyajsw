package io.github.jacobosanchez.nyajsw.cli;

import io.github.jacobosanchez.nyajsw.config.SecretMasker;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.launch.ClasspathExpander;
import io.github.jacobosanchez.nyajsw.launch.JavaCommandBuilder;
import io.github.jacobosanchez.nyajsw.lifecycle.ExecutorScheduler;
import io.github.jacobosanchez.nyajsw.lifecycle.State;
import io.github.jacobosanchez.nyajsw.lifecycle.Supervisor;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;
import io.github.jacobosanchez.nyajsw.platform.ProcessTools;
import io.github.jacobosanchez.nyajsw.platform.ServiceBody;
import io.github.jacobosanchez.nyajsw.platform.ServiceContext;
import io.github.jacobosanchez.nyajsw.platform.ServiceControl;
import io.github.jacobosanchez.nyajsw.platform.ServiceExit;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Service mode ({@code --service}, plan P6.2): what runs as the service under a {@link
 * io.github.jacobosanchez.nyajsw.platform.ServiceHost}, which is connected to the service manager
 * before this reads anything.
 *
 * <p>On the service's thread it reads the configuration, opens the log the configuration describes
 * without a console (there is none), and supervises the application ({@link Supervision}), reporting
 * its progress through a {@link ServiceStatusReporter}. What goes wrong before the configured log is
 * open, and the problems of the log file, go to the service's {@link BootstrapLog}, which is opened
 * only then. The application JVM is started with {@code -Xrs} while {@code
 * wrapper.ntservice.reduce_signals} is true, so that a user logging off does not end it.
 *
 * <p>A stop request of the service manager stops the application through the supervisor's stop
 * chain. Without a graceful strategy nothing asks the application to end (in a service it receives
 * no Ctrl+C), so the stop waits the stop budget and then terminates it; that is said at start.
 *
 * <p>The service stopped as asked when supervision ended {@code STOPPED} after a stop request, or
 * with no exit code to report (the application left running as {@code wrapper.control} allows) or
 * an exit code of 0. Otherwise it failed, with the exit code console mode would have returned
 * ({@link Supervision#exitCode}) as its own: supervision that failed, an application that ended by
 * itself with another code, or a configuration that cannot be read ({@link ExitCode}).
 */
final class ServiceMode implements ServiceBody {

    /** The wait hint while the configuration is read: an include may sit on a slow share. */
    static final Duration READING_WAIT_HINT = Duration.ofSeconds(30);

    /** Where the console log would write: a service has no console. */
    private final PrintStream noConsole = new PrintStream(OutputStream.nullOutputStream());
    private final String file;
    private final JavaCommandBuilder builder;
    private final ClasspathExpander expander;
    private final Function<String, Path> bootstrapFile;
    private final Supplier<ProcessTools> processes;
    private final Object lock = new Object();
    /** Set while the application is supervised; guarded by {@link #lock}. */
    private Supervision supervision;
    /** The configured log while it is open; guarded by {@link #lock}. */
    private WrapperLog log;
    /** Guarded by {@link #lock}. */
    private boolean stopRequested;
    private volatile String serviceName;
    private volatile SecretMasker secrets;
    private volatile ServiceExit exit;

    /**
     * @param file          the configuration file, as given on the command line
     * @param bootstrapFile the bootstrap log of a service name ({@link WrapperLog#bootstrapFile})
     * @param processes     the platform's help with the application's processes, asked for once the
     *                      configured log is open, so that a failure is logged
     */
    ServiceMode(String file, JavaCommandBuilder builder, ClasspathExpander expander,
            Function<String, Path> bootstrapFile, Supplier<ProcessTools> processes) {
        this.file = Objects.requireNonNull(file, "file");
        this.builder = Objects.requireNonNull(builder, "builder");
        this.expander = Objects.requireNonNull(expander, "expander");
        this.bootstrapFile = Objects.requireNonNull(bootstrapFile, "bootstrapFile");
        this.processes = Objects.requireNonNull(processes, "processes");
    }

    @Override
    public ServiceExit run(ServiceContext context) {
        serviceName = context.serviceName();
        try (BootstrapLog bootstrap = bootstrapLog()) {
            context.starting(READING_WAIT_HINT);
            Optional<WrapperConfig> loaded = Main.load(file, bootstrap);
            if (loaded.isEmpty()) {
                bootstrap.fatal("the service '" + serviceName + "' cannot start: its configuration " + file
                        + " cannot be read");
                return ended(new ServiceExit(ExitCode.CONFIGURATION_ERROR.code()));
            }
            WrapperConfig config = loaded.get();
            secrets = config.secrets();
            try (WrapperLog configured = WrapperLog.open(config.log(), noConsole, config.secrets(), bootstrap)) {
                return ended(supervise(context, config, configured));
            }
        }
    }

    private ServiceExit supervise(ServiceContext context, WrapperConfig config, WrapperLog configured) {
        ExecutorScheduler timer = new ExecutorScheduler("nyajsw-service-status");
        ServiceStatusReporter reporter = new ServiceStatusReporter(context, timer, config.probes().readinessInterval(),
                ServiceCommands.startTimeout(config), ServiceCommands.stopTimeout(config), configured);
        try {
            Supervision run = new Supervision(config, configured, builder, expander, wrapperOptions(config), reporter,
                    processes.get());
            boolean stopFirst;
            synchronized (lock) {
                supervision = run;
                log = configured;
                stopFirst = stopRequested;
            }
            if (stopFirst) {
                run.requestStop();
            }
            configured.status(config.secrets().maskText("running as the service '" + serviceName + "'"));
            config.service().name().filter(name -> !name.equalsIgnoreCase(serviceName)).ifPresent(name ->
                    configured.status(config.secrets().maskText("warning: the service runs as '" + serviceName
                            + "', but wrapper.ntservice.name is '" + name + "'")));
            if (!run.hasGracefulStop() && config.lifecycle().stopBudget().isPositive()) {
                configured.status("warning: no graceful stop strategy (wrapper.stop.strategy): a stop of the service"
                        + " waits " + config.lifecycle().stopBudget().toSeconds() + " s, during which nothing asks"
                        + " the application to end, and then terminates it");
            }
            reporter.begin();
            Supervisor.Outcome outcome = run.run();
            reporter.end();
            ServiceExit result = exit(outcome, stopRequested());
            configured.status("supervision ended " + outcome.state() + (result.failed()
                    ? "; the service reports the error code " + result.code() : "; the service has stopped"));
            return result;
        } catch (RuntimeException e) {
            Main.unexpected(e, config, configured);
            return ServiceExit.UNEXPECTED_ERROR;
        } finally {
            reporter.end();
            timer.close();
            synchronized (lock) {
                log = null; // closed by the caller; a late stop request must not write to it
            }
        }
    }

    @Override
    public void stop(ServiceControl control) {
        Supervision target;
        synchronized (lock) {
            stopRequested = true;
            target = supervision;
            if (log != null) {
                log.status("the Service Control Manager asks the service to stop (" + control + ")");
            }
        }
        if (target != null) {
            target.requestStop();
        }
    }

    private boolean stopRequested() {
        synchronized (lock) {
            return stopRequested;
        }
    }

    /** The service's result as a process exit code, once {@link #run} has ended; 1 before. */
    int exitCode() {
        ServiceExit ended = exit;
        return ended == null ? ExitCode.UNEXPECTED_ERROR.code() : ended.code();
    }

    /**
     * The service's bootstrap log: named after the service once the service manager has named it,
     * before that (the process could not even connect) after the configuration file, {@code
     * wrapper.conf} giving {@code wrapper}.
     */
    BootstrapLog bootstrapLog() {
        String name = serviceName != null ? serviceName : fileStem(file);
        return new BootstrapLog(bootstrapFile.apply(name), WrapperLog.bootstrapFile(name));
    }

    /** {@code C:\conf\service.conf} gives {@code service}; worked out on the text, which may not be a valid path. */
    static String fileStem(String file) {
        String name = file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name.isEmpty() ? "service" : name;
    }

    /** {@code text} with the configuration's secrets masked, once it has been read. */
    String mask(String text) {
        SecretMasker known = secrets;
        return known == null ? text : known.maskText(text);
    }

    /** What supervision that ended in {@code outcome} tells the service manager (see the class comment). */
    static ServiceExit exit(Supervisor.Outcome outcome, boolean stopRequested) {
        if (outcome.state() == State.FAILED) {
            return new ServiceExit(Supervision.exitCode(outcome));
        }
        int code = stopRequested ? 0 : Supervision.exitCode(outcome);
        return code == 0 ? ServiceExit.SUCCESS : new ServiceExit(code);
    }

    /** The JVM options the wrapper adds to the application in service mode. */
    static List<String> wrapperOptions(WrapperConfig config) {
        return config.service().reduceSignals() ? List.of("-Xrs") : List.of();
    }

    private ServiceExit ended(ServiceExit result) {
        exit = result;
        return result;
    }
}
