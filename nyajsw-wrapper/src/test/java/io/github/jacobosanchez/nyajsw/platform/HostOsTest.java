package io.github.jacobosanchez.nyajsw.platform;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class HostOsTest {

    @Test
    public void windowsIsRecognisedByItsOsName() {
        assertTrue(HostOs.isWindows("Windows 10"));
        assertTrue(HostOs.isWindows("Windows Server 2022"));
        assertFalse(HostOs.isWindows("Linux"));
        assertFalse(HostOs.isWindows("Mac OS X"));
        assertFalse(HostOs.isWindows(""));
    }

    @Test
    public void currentHostAgreesWithTheFileSeparator() {
        assertEquals(HostOs.isWindows(), java.io.File.separatorChar == '\\');
    }
}
