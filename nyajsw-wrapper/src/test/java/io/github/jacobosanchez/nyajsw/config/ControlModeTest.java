package io.github.jacobosanchez.nyajsw.config;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ControlModeTest {

    /** The couplings as YAJSW documents {@code wrapper.control}. */
    @DataProvider
    public Object[][] modes() {
        return new Object[][] {
                // mode, wrapper ends with the application, application ends with the wrapper
                {ControlMode.TIGHT, true, true},
                {ControlMode.WRAPPER, true, false},
                {ControlMode.APPLICATION, false, true},
                {ControlMode.LOOSE, false, false},
        };
    }

    @Test(dataProvider = "modes")
    public void couplingFollowsTheMode(ControlMode mode, boolean wrapperEndsWithApplication,
            boolean applicationEndsWithWrapper) {
        assertEquals(mode.wrapperEndsWithApplication(), wrapperEndsWithApplication);
        assertEquals(mode.applicationEndsWithWrapper(), applicationEndsWithWrapper);
    }
}
