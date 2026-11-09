package io.jsl.nyajsw.platform.unsupported;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

import io.jsl.nyajsw.platform.ServiceController;
import io.jsl.nyajsw.platform.UnsupportedPlatformException;

import java.time.Duration;
import java.util.Map;

import org.testng.annotations.Test;

public class UnsupportedPlatformTest {

    @Test
    public void everyServiceOperationFailsNamingTheHostAndWhatWorks() {
        ServiceController services = new UnsupportedPlatform("Linux").serviceController();
        Map<String, Runnable> operations = Map.of(
                "install", () -> services.install(null),
                "remove", () -> services.remove("svc"),
                "start", () -> services.start("svc", Duration.ofSeconds(30)),
                "stop", () -> services.stop("svc", Duration.ofSeconds(30)),
                "query", () -> services.status("svc"));

        operations.forEach((operation, call) -> {
            UnsupportedPlatformException failure = expectThrows(UnsupportedPlatformException.class, call::run);

            assertEquals(failure.getMessage(), "cannot " + operation + " a service: nyajsw runs services on Windows"
                    + " only, and this host runs Linux; the console mode (-c) works here");
        });
    }

    @Test
    public void runningAsAServiceFailsNamingTheHostAndWhatWorks() {
        UnsupportedPlatformException failure = expectThrows(UnsupportedPlatformException.class,
                () -> new UnsupportedPlatform("Linux").serviceHost().run(null));

        assertEquals(failure.getMessage(), "cannot run as a service: nyajsw runs services on Windows only, and this"
                + " host runs Linux; the console mode (-c) works here");
    }

    @Test
    public void aBlankOsNameIsAnUnknownOperatingSystem() {
        UnsupportedPlatform platform = new UnsupportedPlatform(" ");

        assertEquals(platform.name(), "an unknown operating system");
        assertEquals(expectThrows(UnsupportedPlatformException.class,
                () -> platform.serviceController().start("svc", Duration.ofSeconds(30))).getMessage(),
                "cannot start a service: nyajsw runs services on Windows only, and this host runs an unknown"
                        + " operating system; the console mode (-c) works here");
    }
}
