package io.github.jacobosanchez.nyajsw.cli;

import io.github.jacobosanchez.nyajsw.config.ConfigWarning;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.lifecycle.Supervisor;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;
import io.github.jacobosanchez.nyajsw.platform.ServiceController;
import io.github.jacobosanchez.nyajsw.platform.ServiceState;
import io.github.jacobosanchez.nyajsw.platform.ServiceStatus;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The service commands {@code -i -r -t -p -q} (plan §3.2): each one runs against the host's {@link
 * ServiceController} and reports on the console only, so that a command run beside a running
 * service never rolls or writes that service's log file. Failures are thrown, for the entry point
 * to report and map to an exit code ({@link ExitCode#of}).
 */
final class ServiceCommands {

    /** The commands, by their switches. */
    enum Action {
        INSTALL("-i", "--install"),
        REMOVE("-r", "--remove"),
        START("-t", "--start"),
        STOP("-p", "--stop"),
        QUERY("-q", "--query");

        private final Set<String> switches;

        Action(String... switches) {
            this.switches = Set.of(switches);
        }

        /** The action of {@code argument}, if it is one of the switches. */
        static Optional<Action> of(String argument) {
            return Arrays.stream(values()).filter(action -> action.switches.contains(argument)).findFirst();
        }
    }

    /**
     * What {@link Action#START} allows beyond {@code wrapper.startup.delay} and {@code
     * wrapper.startup.timeout}: the service's own JVM starts, reads its configuration and launches
     * the application before that timeout begins.
     */
    static final Duration START_MARGIN = Duration.ofSeconds(30);

    /** How long {@code -t} waits without a limit, for {@code wrapper.startup.timeout=0}. */
    static final Duration NO_LIMIT = Supervisor.LONGEST_WAIT;

    private final ServiceController services;
    private final WrapperLog log;

    ServiceCommands(ServiceController services, WrapperLog log) {
        this.services = Objects.requireNonNull(services, "services");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** Runs {@code action} for the service of {@code config}; a failure is thrown. */
    void run(Action action, WrapperConfig config) {
        for (ConfigWarning warning : config.warnings()) {
            log.status("warning: " + warning);
        }
        String name = config.service().requiredName();
        switch (action) {
            case INSTALL -> {
                List<String> warnings = services.install(config);
                warnings.forEach(warning -> log.status("warning: " + config.secrets().maskText(warning)));
                log.info("installed the service '" + name + "'");
            }
            case REMOVE -> {
                services.remove(name);
                log.info("removed the service '" + name + "'; Windows deletes it once it has stopped and no"
                        + " program has it open");
            }
            case START -> {
                services.start(name, startTimeout(config));
                log.info("started the service '" + name + "'");
            }
            case STOP -> {
                services.stop(name, stopTimeout(config));
                log.info("stopped the service '" + name + "'");
            }
            case QUERY -> log.info(describe(name, services.status(name)));
        }
    }

    /**
     * How long {@code -t} waits for {@code RUNNING}, which the service reports once it is ready: the
     * startup delay, the startup timeout and {@link #START_MARGIN}, without a limit when the startup
     * has none. Restarts before the application was ever ready can take longer (plan, P5.4).
     */
    static Duration startTimeout(WrapperConfig config) {
        Duration startup = config.lifecycle().startupTimeout();
        return startup.isZero() ? NO_LIMIT : Supervisor.sum(config.lifecycle().startupDelay(), startup, START_MARGIN);
    }

    /** How long {@code -p} waits for {@code STOPPED}: as long as a stop may take ({@link Supervisor#stopWait}). */
    static Duration stopTimeout(WrapperConfig config) {
        return Supervisor.stopWait(config.lifecycle().stopBudget());
    }

    /** A query's answer, such as {@code "the service 'svc' is running (pid 4242)"}. */
    static String describe(String name, Optional<ServiceStatus> status) {
        if (status.isEmpty()) {
            return "the service '" + name + "' is not installed";
        }
        ServiceState state = status.get().state();
        String pid = status.get().pid().isPresent() ? " (pid " + status.get().pid().getAsLong() + ")" : "";
        return "the service '" + name + "' is " + state.name().toLowerCase(Locale.ROOT).replace('_', ' ') + pid;
    }
}
