package io.jsl.nyajsw.platform.unsupported;

import io.jsl.nyajsw.config.WrapperConfig;
import io.jsl.nyajsw.platform.ServiceController;
import io.jsl.nyajsw.platform.ServiceStatus;
import io.jsl.nyajsw.platform.UnsupportedPlatformException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The service commands of a host without services: each one fails, saying what does work. */
final class UnsupportedServiceController implements ServiceController {

    private final String platformName;

    UnsupportedServiceController(String platformName) {
        this.platformName = Objects.requireNonNull(platformName, "platformName");
    }

    @Override
    public List<String> install(WrapperConfig config) {
        throw unsupported("install");
    }

    @Override
    public void remove(String serviceName) {
        throw unsupported("remove");
    }

    @Override
    public void start(String serviceName, Duration timeout) {
        throw unsupported("start");
    }

    @Override
    public void stop(String serviceName, Duration timeout) {
        throw unsupported("stop");
    }

    @Override
    public Optional<ServiceStatus> status(String serviceName) {
        throw unsupported("query");
    }

    private UnsupportedPlatformException unsupported(String operation) {
        return new UnsupportedPlatformException("cannot " + operation + " a service: nyajsw runs services on"
                + " Windows only, and this host runs " + platformName + "; the console mode (-c) works here");
    }
}
