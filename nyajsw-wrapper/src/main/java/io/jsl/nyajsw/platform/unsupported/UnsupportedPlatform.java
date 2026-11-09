package io.jsl.nyajsw.platform.unsupported;

import io.jsl.nyajsw.platform.Platform;
import io.jsl.nyajsw.platform.ProcessTools;
import io.jsl.nyajsw.platform.ServiceController;
import io.jsl.nyajsw.platform.ServiceHost;

import java.util.Objects;

/**
 * A host nyajsw has no platform for: the console mode runs (it needs nothing beyond the JDK), while
 * the service commands and the service host fail with an {@link io.jsl.nyajsw.platform.UnsupportedPlatformException}
 * (plan §4.3).
 */
public final class UnsupportedPlatform implements Platform {

    private final String name;
    private final ServiceController services;
    private final ServiceHost host;

    /** @param osName the host's {@code os.name}, named in messages */
    public UnsupportedPlatform(String osName) {
        this.name = Objects.requireNonNull(osName, "osName").isBlank() ? "an unknown operating system" : osName;
        this.services = new UnsupportedServiceController(name);
        this.host = new UnsupportedServiceHost(name);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ServiceController serviceController() {
        return services;
    }

    @Override
    public ServiceHost serviceHost() {
        return host;
    }

    @Override
    public ProcessTools processTools() {
        return ProcessTools.NONE;
    }
}
