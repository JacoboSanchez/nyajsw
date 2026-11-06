package io.github.jacobosanchez.nyajsw;

import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.lang.management.ManagementFactory;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Verifies the test toolchain every wrapper test relies on: TestNG runs, Mockito's inline mock maker
 * is loaded as a {@code -javaagent} at JVM start (no JDK 21 dynamic-agent warning) and can mock
 * final classes, and mocks are initialised with {@link MockitoAnnotations#openMocks} per method.
 */
public class WrapperToolchainSmokeTest {

    /** Final on purpose: only the inline mock maker can mock it. */
    static final class FinalCollaborator {
        String name() {
            return "real";
        }
    }

    @Mock
    private FinalCollaborator collaborator;

    private AutoCloseable mocks;

    @BeforeMethod
    public void openMocks() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterMethod(alwaysRun = true)
    public void closeMocks() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    public void mockitoAgentIsLoadedAtStartup() {
        boolean agentOnCommandLine = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .anyMatch(arg -> arg.startsWith("-javaagent:") && arg.contains("mockito-core"));
        assertTrue(agentOnCommandLine, "surefire argLine must load mockito-core as -javaagent");
    }

    @Test
    public void inlineMockMakerMocksFinalClasses() {
        when(collaborator.name()).thenReturn("mocked");

        assertEquals(collaborator.name(), "mocked");
    }
}
