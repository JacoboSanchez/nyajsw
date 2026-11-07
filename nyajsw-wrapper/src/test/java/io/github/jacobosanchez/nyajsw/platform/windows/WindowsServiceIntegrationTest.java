package io.github.jacobosanchez.nyajsw.platform.windows;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.TestConfigs;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.platform.HostOs;
import io.github.jacobosanchez.nyajsw.platform.ServiceBody;
import io.github.jacobosanchez.nyajsw.platform.ServiceContext;
import io.github.jacobosanchez.nyajsw.platform.ServiceControl;
import io.github.jacobosanchez.nyajsw.platform.ServiceException;
import io.github.jacobosanchez.nyajsw.platform.ServiceExit;
import io.github.jacobosanchez.nyajsw.platform.ServiceState;
import io.github.jacobosanchez.nyajsw.platform.ServiceStatus;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Install, query and remove a throwaway service against the real Service Control Manager (plan
 * P5.6), and read what was registered back from the registry, where the Service Control Manager
 * keeps it ({@code HKLM\SYSTEM\CurrentControlSet\Services\<name>}): only this test sees whether the
 * native calls stored what the configuration says. Excluded by default; run with {@code mvn -q
 * verify -Pwindows-integration} in an elevated shell. Starting and stopping the service follow in P6,
 * once {@code --service} runs it.
 *
 * <p>Every service is named {@code nyajsw-test-<random>} and removed after the test, also when it
 * fails. A test JVM killed in between can leave one behind: {@code sc query state= all | findstr
 * nyajsw-test-} finds it, {@code sc delete <name>} removes it.
 */
@Test(groups = "windows-integration")
public class WindowsServiceIntegrationTest {

    private static final int SERVICE_WIN32_OWN_PROCESS = 0x10;
    private static final int SERVICE_AUTO_START = 2;
    private static final int SERVICE_DEMAND_START = 3;

    private Path dir;
    private String name;
    private ServiceCommandLine commandLine;
    private WindowsServiceController controller;

    @BeforeMethod(alwaysRun = true)
    public void setUp() throws IOException {
        dir = null; // one instance runs every method: nothing may be left from the one before
        name = null;
        controller = null;
        if (!HostOs.isWindows()) {
            throw new SkipException("the Service Control Manager exists on Windows only");
        }
        dir = Files.createTempDirectory("nyajsw-it").toRealPath();
        name = "nyajsw-test-" + UUID.randomUUID().toString().substring(0, 8);
        // The test JVM's java.class.path is surefire's booter jar; the service would need the real jars,
        // which do not matter until P6 starts the service.
        commandLine = new ServiceCommandLine(Path.of(System.getProperty("java.home")),
                List.of(Path.of("target", "classes")), ServiceCommandLine.nativeCharset());
        controller = new WindowsServiceController(new JnaScmApi(), new JnaLsaApi(), commandLine, StateWait.REAL);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        try {
            if (controller != null && name != null) {
                controller.remove(name);
            }
        } catch (ServiceException e) {
            int code = e.errorCode();
            if (code != Win32Errors.SERVICE_DOES_NOT_EXIST && code != Win32Errors.SERVICE_MARKED_FOR_DELETE) {
                throw e;
            }
        } finally {
            if (dir != null) {
                TestConfigs.delete(dir);
            }
        }
    }

    private WrapperConfig config(String startType) throws IOException {
        return TestConfigs.load(dir, "wrapper.ntservice.name=" + name, "wrapper.ntservice.displayname=" + name + " display",
                "wrapper.ntservice.description=nyajsw integration test; safe to delete",
                "wrapper.ntservice.starttype=" + startType, "wrapper.ntservice.dependency.1=Tcpip",
                "wrapper.ntservice.dependency.2=Afd", "wrapper.shutdown.timeout=40");
    }

    private String key() {
        return "SYSTEM\\CurrentControlSet\\Services\\" + name;
    }

    private int dword(String value) {
        return Advapi32Util.registryGetIntValue(WinReg.HKEY_LOCAL_MACHINE, key(), value);
    }

    private String text(String value) {
        return Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key(), value);
    }

    @Test
    public void anInstalledServiceIsRegisteredAsConfiguredAndGoesWhenRemoved() throws Exception {
        WrapperConfig config = config("DEMAND_START");

        controller.install(config);

        assertEquals(controller.status(name), Optional.of(new ServiceStatus(ServiceState.STOPPED, OptionalLong.empty())));
        assertEquals(Advapi32Util.registryGetExpandableStringValue(WinReg.HKEY_LOCAL_MACHINE, key(), "ImagePath"),
                commandLine.build(config).binaryPath());
        assertEquals(text("DisplayName"), name + " display");
        assertEquals(text("Description"), "nyajsw integration test; safe to delete");
        assertEquals(text("ObjectName"), "LocalSystem");
        assertEquals(dword("Type"), SERVICE_WIN32_OWN_PROCESS);
        assertEquals(dword("Start"), SERVICE_DEMAND_START);
        assertEquals(new ArrayList<>(List.of(Advapi32Util.registryGetStringArray(WinReg.HKEY_LOCAL_MACHINE, key(),
                "DependOnService"))), List.of("Tcpip", "Afd"));
        assertEquals(dword("PreshutdownTimeout"), JnaScmApi.dword(WindowsServiceController.preshutdownTimeout(config)));

        controller.remove(name);

        assertTrue(gone(), name + " is still installed after removal");
    }

    @Test
    public void aDelayedServiceIsAutomaticWithTheDelayFlag() throws Exception {
        controller.install(config("DELAYED_AUTO_START"));

        assertEquals(dword("Start"), SERVICE_AUTO_START);
        assertEquals(dword("DelayedAutostart"), 1);
    }

    @Test
    public void aSecondInstallOfTheSameNameIsRefusedAndKeepsTheFirst() throws Exception {
        controller.install(config("DEMAND_START"));

        ServiceException failure = expectThrows(ServiceException.class, () -> controller.install(config("DEMAND_START")));

        assertEquals(failure.errorCode(), Win32Errors.SERVICE_EXISTS);
        assertTrue(controller.status(name).isPresent(), "the refused install must not remove the installed service");
    }

    @Test
    public void aServiceThatIsNotInstalledHasNoStatus() {
        assertFalse(controller.status(name).isPresent());
    }

    @Test
    public void aProcessTheServiceControlManagerDidNotStartCannotRunAsAService() {
        ServiceException failure = expectThrows(ServiceException.class,
                () -> new WindowsServiceHost(new JnaServiceHostApi()).run(new ServiceBody() {
                    @Override
                    public ServiceExit run(ServiceContext context) {
                        throw new AssertionError("the service must not run");
                    }

                    @Override
                    public void stop(ServiceControl control) {
                    }
                }));

        assertEquals(failure.errorCode(), Win32Errors.FAILED_SERVICE_CONTROLLER_CONNECT);
    }

    /** Whether the service is gone; Windows deletes it once every handle to it is closed. */
    private boolean gone() throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (controller.status(name).isEmpty()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
