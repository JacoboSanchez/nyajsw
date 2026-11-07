package io.github.jacobosanchez.nyajsw.platform;

import io.github.jacobosanchez.nyajsw.config.WrapperConfig;

import java.time.Duration;
import java.util.Optional;

/**
 * Installs, removes, starts, stops and queries the service that runs a configuration: the client
 * side of the service commands ({@code -i -r -t -p -q}, plan §3.2). The service itself is run by
 * the service host (P6), not through this interface.
 *
 * <p>Failures are {@link io.github.jacobosanchez.nyajsw.NyajswException}s: an
 * {@link UnsupportedPlatformException} where the host has no services, a {@code ServiceException}
 * with the operating system's error code and text where the service manager refuses (P5.2).
 * Starting a service that runs already and stopping one that does not run are refusals too, so a
 * script learns that nothing happened.
 */
public interface ServiceController {

    /**
     * Registers the service described by {@code config} ({@code wrapper.ntservice.*}), set to run
     * the wrapper on {@link WrapperConfig#file()}.
     */
    void install(WrapperConfig config);

    /** Removes the service named {@code serviceName}; one that still runs is removed once it stops. */
    void remove(String serviceName);

    /**
     * Starts the service named {@code serviceName} and waits until it runs: it fails when the
     * service stops again, or is still starting after {@code timeout}.
     */
    void start(String serviceName, Duration timeout);

    /**
     * Stops the service named {@code serviceName} and waits until it has stopped: it fails when the
     * service is still stopping after {@code timeout}.
     */
    void stop(String serviceName, Duration timeout);

    /**
     * The state of the service named {@code serviceName}; empty when no such service is installed,
     * which is an answer to a query, not a failure. A state the service manager reports that
     * {@link ServiceState} does not know is a failure.
     */
    Optional<ServiceStatus> status(String serviceName);
}
