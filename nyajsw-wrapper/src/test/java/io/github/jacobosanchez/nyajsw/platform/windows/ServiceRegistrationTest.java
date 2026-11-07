package io.github.jacobosanchez.nyajsw.platform.windows;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;

import io.github.jacobosanchez.nyajsw.config.Secret;
import io.github.jacobosanchez.nyajsw.config.StartType;

import java.util.List;
import java.util.Optional;

import org.testng.annotations.Test;

public class ServiceRegistrationTest {

    private static ServiceRegistration withDependencies(List<String> dependencies) {
        return new ServiceRegistration("svc", "Service", "\"C:\\j\\javaw.exe\" -Dp=hunter2", StartType.AUTO_START,
                dependencies, Optional.empty(), Optional.of(new Secret("hunter2")));
    }

    @Test
    public void dependenciesAreEachEndedByANul() {
        assertEquals(withDependencies(List.of("Tcpip", "Afd")).dependencyList(), "Tcpip\0Afd\0");
        assertEquals(withDependencies(List.of("Tcpip")).dependencyList(), "Tcpip\0");
    }

    @Test
    public void aDelayedServiceRegistersAsAutomatic() {
        assertEquals(withStartType(StartType.AUTO_START).scmStartType(), 2);
        assertEquals(withStartType(StartType.DELAYED_AUTO_START).scmStartType(), 2);
        assertEquals(withStartType(StartType.DEMAND_START).scmStartType(), 3);
        assertEquals(withStartType(StartType.DISABLED).scmStartType(), 4);
    }

    private static ServiceRegistration withStartType(StartType startType) {
        return new ServiceRegistration("svc", "Service", "x", startType, List.of(), Optional.empty(), Optional.empty());
    }

    @Test
    public void noDependenciesIsNull() {
        assertNull(withDependencies(List.of()).dependencyList());
    }

    @Test
    public void theTextFormShowsNeitherTheBinaryPathNorThePassword() {
        String text = withDependencies(List.of()).toString();

        assertEquals(text, "ServiceRegistration[svc, values hidden]");
        assertFalse(text.contains("hunter2"));
    }
}
