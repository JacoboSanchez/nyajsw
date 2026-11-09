package io.jsl.nyajsw.platform.windows;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

/** What {@link JnaLsaApi} computes itself; its calls are tested against the real system (P5.6). */
public class JnaLsaApiTest {

    @Test
    public void aLocalAccountIsLookedUpOnThisComputerOnly() {
        assertEquals(JnaLsaApi.lookupName(".\\svcuser", () -> "HOST1"), "HOST1\\svcuser");
        assertEquals(JnaLsaApi.lookupName("DOMAIN\\svcuser", () -> "HOST1"), "DOMAIN\\svcuser");
        assertEquals(JnaLsaApi.lookupName("svcuser", () -> "HOST1"), "svcuser");
    }
}
