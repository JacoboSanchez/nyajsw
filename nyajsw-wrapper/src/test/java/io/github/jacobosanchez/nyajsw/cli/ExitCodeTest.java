package io.github.jacobosanchez.nyajsw.cli;

import static org.testng.Assert.assertEquals;

import io.github.jacobosanchez.nyajsw.NyajswException;
import io.github.jacobosanchez.nyajsw.config.ConfigException;
import io.github.jacobosanchez.nyajsw.config.ConfigLocation;
import io.github.jacobosanchez.nyajsw.launch.LaunchException;
import io.github.jacobosanchez.nyajsw.platform.PlatformException;
import io.github.jacobosanchez.nyajsw.platform.UnsupportedPlatformException;

import org.testng.annotations.Test;

public class ExitCodeTest {

    @Test
    public void theCodesAreThoseOfThePlan() {
        assertEquals(ExitCode.SUCCESS.code(), 0);
        assertEquals(ExitCode.UNEXPECTED_ERROR.code(), 1);
        assertEquals(ExitCode.CONFIGURATION_ERROR.code(), 2);
        assertEquals(ExitCode.SERVICE_OPERATION_FAILED.code(), 3);
        assertEquals(ExitCode.UNSUPPORTED_PLATFORM.code(), 4);
    }

    @Test
    public void eachFailureEndsWithItsCode() {
        assertEquals(ExitCode.of(new UnsupportedPlatformException("no services here")), ExitCode.UNSUPPORTED_PLATFORM);
        assertEquals(ExitCode.of(new ConfigException(ConfigLocation.of("wrapper.conf"), "bad")),
                ExitCode.CONFIGURATION_ERROR);
        assertEquals(ExitCode.of(new PlatformException("the system refused")), ExitCode.UNEXPECTED_ERROR);
        assertEquals(ExitCode.of(new LaunchException("cannot start")), ExitCode.UNEXPECTED_ERROR);
        assertEquals(ExitCode.of(new NyajswException("other")), ExitCode.UNEXPECTED_ERROR);
    }
}
