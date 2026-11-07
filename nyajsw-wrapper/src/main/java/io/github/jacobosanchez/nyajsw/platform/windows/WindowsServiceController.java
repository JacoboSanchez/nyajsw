package io.github.jacobosanchez.nyajsw.platform.windows;

import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.JAVA_APP_MAINCLASS;
import static io.github.jacobosanchez.nyajsw.config.ConfigKeys.NTSERVICE_NAME;

import io.github.jacobosanchez.nyajsw.config.ConfigException;
import io.github.jacobosanchez.nyajsw.config.ServiceConfig;
import io.github.jacobosanchez.nyajsw.config.StartType;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.lifecycle.Supervisor;
import io.github.jacobosanchez.nyajsw.platform.ServiceController;
import io.github.jacobosanchez.nyajsw.platform.ServiceException;
import io.github.jacobosanchez.nyajsw.platform.ServiceStatus;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The service commands against the Windows Service Control Manager (plan §4.4), through {@link
 * ScmApi}. Each command opens the manager and the service with the least access it needs, so that
 * a query works without elevation, and closes both handles whatever happens.
 *
 * <p>Install registers an own-process service running the {@link ServiceCommandLine}'s binary path, then
 * sets its description, the delayed start of {@code DELAYED_AUTO_START} and, always, the
 * pre-shutdown timeout: the longest a stop can take (the stop budget, the supervisor's wait after
 * terminating, {@link #STOPPED_MARGIN} to report it), since the Windows default is only 10 s. A
 * service that cannot be configured is deleted again, so that a failed install leaves nothing.
 */
final class WindowsServiceController implements ServiceController {

    /** What the pre-shutdown timeout allows beyond the longest stop, for the wrapper to report and exit. */
    static final Duration STOPPED_MARGIN = Duration.ofSeconds(10);

    private final ScmApi scm;
    private final ServiceCommandLine commandLine;
    private final StateWait wait;

    WindowsServiceController(ScmApi scm, ServiceCommandLine commandLine, StateWait wait) {
        this.scm = Objects.requireNonNull(scm, "scm");
        this.commandLine = Objects.requireNonNull(commandLine, "commandLine");
        this.wait = Objects.requireNonNull(wait, "wait");
    }

    @Override
    public List<String> install(WrapperConfig config) {
        ServiceConfig service = config.service();
        String name = service.name().orElseThrow(() -> new ConfigException(NTSERVICE_NAME.name() + " is not set,"
                + " and there is no " + JAVA_APP_MAINCLASS.name() + " to name the service after"));
        ServiceCommand command = commandLine.build(config);
        ServiceRegistration registration = new ServiceRegistration(name, service.displayName().orElse(name),
                command.binaryPath(), service.startType(), service.dependencies(), service.account(),
                service.password());
        String what = "install the service '" + name + "'";
        ScmHandle manager = call(() -> scm.openManager(ScmApi.SC_MANAGER_CREATE_SERVICE), what);
        try {
            ScmHandle created = call(() -> scm.createService(manager, registration,
                    ScmApi.SERVICE_CHANGE_CONFIG | ScmApi.DELETE), what);
            try {
                configure(created, service, preshutdownTimeout(config));
            } catch (RuntimeException e) { // whatever went wrong, a half-configured service is deleted
                try {
                    scm.deleteService(created);
                } catch (RuntimeException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e instanceof ServiceException refused ? failure(what, refused) : e;
            } finally {
                scm.close(created);
            }
        } finally {
            scm.close(manager);
        }
        return command.warnings();
    }

    private void configure(ScmHandle created, ServiceConfig service, Duration preshutdownTimeout) {
        service.description().ifPresent(description -> scm.setDescription(created, description));
        if (service.startType() == StartType.DELAYED_AUTO_START) {
            scm.setDelayedAutoStart(created, true);
        }
        scm.setPreshutdownTimeout(created, preshutdownTimeout);
    }

    /** The longest a stop of {@code config}'s application can take, and the time to report it. */
    static Duration preshutdownTimeout(WrapperConfig config) {
        return Supervisor.longestStop(config.lifecycle().stopBudget()).plus(STOPPED_MARGIN);
    }

    @Override
    public void remove(String serviceName) {
        withService(serviceName, ScmApi.DELETE, "remove the service '" + serviceName + "'", service -> {
            scm.deleteService(service);
            return null;
        });
    }

    @Override
    public void start(String serviceName, Duration timeout) {
        String what = "start the service '" + serviceName + "'";
        withService(serviceName, ScmApi.SERVICE_START | ScmApi.SERVICE_QUERY_STATUS, what, service -> {
            scm.startService(service);
            return wait.until(() -> scm.queryStatus(service), ScmApi.SERVICE_RUNNING, ScmApi.SERVICE_STOPPED,
                    timeout);
        });
    }

    @Override
    public void stop(String serviceName, Duration timeout) {
        String what = "stop the service '" + serviceName + "'";
        withService(serviceName, ScmApi.SERVICE_STOP | ScmApi.SERVICE_QUERY_STATUS, what, service -> {
            scm.stopService(service);
            return wait.until(() -> scm.queryStatus(service), ScmApi.SERVICE_STOPPED, 0, timeout);
        });
    }

    @Override
    public Optional<ServiceStatus> status(String serviceName) {
        String what = "query the service '" + serviceName + "'";
        ScmHandle manager = call(() -> scm.openManager(ScmApi.SC_MANAGER_CONNECT), what);
        try {
            ScmHandle service;
            try {
                service = scm.openService(manager, serviceName, ScmApi.SERVICE_QUERY_STATUS);
            } catch (ServiceException e) {
                if (e.errorCode() == Win32Errors.SERVICE_DOES_NOT_EXIST) {
                    return Optional.empty();
                }
                throw failure(what, e);
            }
            try {
                return Optional.of(call(() -> {
                    ScmStatus status = scm.queryStatus(service);
                    return new ServiceStatus(ServiceStates.of(status.currentState()), status.processId() == 0
                            ? OptionalLong.empty() : OptionalLong.of(Integer.toUnsignedLong(status.processId())));
                }, what));
            } finally {
                scm.close(service);
            }
        } finally {
            scm.close(manager);
        }
    }

    /** Runs {@code action} on the service opened with {@code access}, closing both handles after. */
    private <T> T withService(String serviceName, int access, String what, Function<ScmHandle, T> action) {
        ScmHandle manager = call(() -> scm.openManager(ScmApi.SC_MANAGER_CONNECT), what);
        try {
            ScmHandle service = call(() -> scm.openService(manager, serviceName, access), what);
            try {
                return call(() -> action.apply(service), what);
            } finally {
                scm.close(service);
            }
        } finally {
            scm.close(manager);
        }
    }

    /** {@code call}'s result; a failure of the Service Control Manager says what was attempted. */
    private static <T> T call(Supplier<T> call, String what) {
        try {
            return call.get();
        } catch (ServiceException e) {
            throw failure(what, e);
        }
    }

    private static ServiceException failure(String what, ServiceException cause) {
        return new ServiceException("cannot " + what + ": " + cause.getMessage(), cause.errorCode(), cause);
    }
}
