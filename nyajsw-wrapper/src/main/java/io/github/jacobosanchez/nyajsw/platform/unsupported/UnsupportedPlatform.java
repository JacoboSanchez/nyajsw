package io.github.jacobosanchez.nyajsw.platform.unsupported;

import io.github.jacobosanchez.nyajsw.platform.Platform;
import io.github.jacobosanchez.nyajsw.platform.ServiceController;

import java.util.Objects;

/**
 * A host nyajsw has no platform for: the console mode runs (it needs nothing beyond the JDK), while
 * the service commands fail with an {@link io.github.jacobosanchez.nyajsw.platform.UnsupportedPlatformException}
 * (plan §4.3).
 */
public final class UnsupportedPlatform implements Platform {

    private final String name;
    private final ServiceController services;

    /** @param osName the host's {@code os.name}, named in messages */
    public UnsupportedPlatform(String osName) {
        this.name = Objects.requireNonNull(osName, "osName").isBlank() ? "an unknown operating system" : osName;
        this.services = new UnsupportedServiceController(name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ServiceController serviceController() {
        return services;
    }
}
