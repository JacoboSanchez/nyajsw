package io.jsl.nyajsw.platform;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

import java.util.OptionalLong;

import org.testng.annotations.Test;

public class ServiceStatusTest {

    @Test
    public void aRunningServiceHasItsProcessAndAStoppedOneNone() {
        assertEquals(new ServiceStatus(ServiceState.RUNNING, OptionalLong.of(4242)).pid(), OptionalLong.of(4242));
        assertEquals(new ServiceStatus(ServiceState.STOPPED, OptionalLong.empty()).pid(), OptionalLong.empty());
    }

    @Test
    public void aProcessIdIsPositive() {
        expectThrows(IllegalArgumentException.class, () -> new ServiceStatus(ServiceState.RUNNING, OptionalLong.of(0)));
        expectThrows(NullPointerException.class, () -> new ServiceStatus(null, OptionalLong.empty()));
    }
}
