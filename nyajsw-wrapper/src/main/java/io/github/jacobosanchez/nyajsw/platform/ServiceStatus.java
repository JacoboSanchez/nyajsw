package io.github.jacobosanchez.nyajsw.platform;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * What {@link ServiceController#status} found out about an installed service.
 *
 * @param state the service's state
 * @param pid   the process the service runs in, while it has one
 */
public record ServiceStatus(ServiceState state, OptionalLong pid) {

    public ServiceStatus {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(pid, "pid");
        if (pid.isPresent() && pid.getAsLong() <= 0) {
            throw new IllegalArgumentException("not a process id: " + pid.getAsLong());
        }
    }
}
