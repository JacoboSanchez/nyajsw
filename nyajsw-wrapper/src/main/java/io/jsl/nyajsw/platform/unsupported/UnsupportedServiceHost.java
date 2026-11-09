package io.jsl.nyajsw.platform.unsupported;

import io.jsl.nyajsw.platform.ServiceBody;
import io.jsl.nyajsw.platform.ServiceHost;
import io.jsl.nyajsw.platform.UnsupportedPlatformException;

import java.util.Objects;

/** The service host of a host without services: it fails, saying what does work. */
final class UnsupportedServiceHost implements ServiceHost {

    private final String platformName;

    UnsupportedServiceHost(String platformName) {
        this.platformName = Objects.requireNonNull(platformName, "platformName");
    }

    @Override
    public void run(ServiceBody body) {
        throw new UnsupportedPlatformException("cannot run as a service: nyajsw runs services on Windows only, and"
                + " this host runs " + platformName + "; the console mode (-c) works here");
    }
}
