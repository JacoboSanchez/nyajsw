package io.jsl.nyajsw.cli;

import static org.testng.Assert.assertEquals;

import io.jsl.nyajsw.NyajswException;
import io.jsl.nyajsw.config.ConfigException;
import io.jsl.nyajsw.config.ConfigLocation;
import io.jsl.nyajsw.launch.LaunchException;
import io.jsl.nyajsw.platform.PlatformException;
import io.jsl.nyajsw.platform.ServiceException;
import io.jsl.nyajsw.platform.UnsupportedPlatformException;

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
        assertEquals(ExitCode.of(new ServiceException("the service manager refused", 5)),
                ExitCode.SERVICE_OPERATION_FAILED);
        assertEquals(ExitCode.of(new PlatformException("the system refused")), ExitCode.UNEXPECTED_ERROR);
        assertEquals(ExitCode.of(new LaunchException("cannot start")), ExitCode.UNEXPECTED_ERROR);
        assertEquals(ExitCode.of(new NyajswException("other")), ExitCode.UNEXPECTED_ERROR);
    }
}
