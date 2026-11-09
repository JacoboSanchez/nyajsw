package io.jsl.nyajsw.platform.windows;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

public class Win32ErrorsTest {

    @Test
    public void theSystemTextComesFirstWithTheCodeAndAHint() {
        assertEquals(Win32Errors.describe(5, "Access is denied.\r\n"),
                "Access is denied (Win32 error 5); run the command as Administrator");
        assertEquals(Win32Errors.describe(1069, "The service did not start due to a logon failure."),
                "The service did not start due to a logon failure (Win32 error 1069); check"
                        + " wrapper.ntservice.password and that the account may log on as a service");
    }

    @Test
    public void withoutSystemTextAKnownErrorIsNamed() {
        assertEquals(Win32Errors.describe(1060, ""), "the service is not installed (Win32 error 1060)");
        assertEquals(Win32Errors.describe(1072, null), "the service is marked for deletion (Win32 error 1072);"
                + " close the Services console and any other program that has the service open, then try again");
    }

    @Test
    public void aServiceProcessThatEndedUnexpectedlyPointsToTheLogs() {
        assertEquals(Win32Errors.describe(1067, ""), "the service process ended unexpectedly (Win32 error 1067);"
                + " see the wrapper log and the bootstrap log of the service");
    }

    @Test
    public void aServiceRunOutsideTheServiceManagerIsToldWhatToRunInstead() {
        assertEquals(Win32Errors.describe(1063, ""), "the process was not started as a service (Win32 error 1063);"
                + " --service is for the Service Control Manager: start the installed service with -t, or run the"
                + " application in the console with -c");
    }

    @Test
    public void theHintCanBeLeftOutForCallsThatAreNotAboutServices() {
        assertEquals(Win32Errors.describe(5, "Access is denied.", false), "Access is denied (Win32 error 5)");
    }

    @Test
    public void anUnknownErrorKeepsItsSystemTextOrIsJustAnError() {
        assertEquals(Win32Errors.describe(87, "The parameter is incorrect."), "The parameter is incorrect (Win32 error 87)");
        assertEquals(Win32Errors.describe(12345, " "), "error (Win32 error 12345)");
    }
}
