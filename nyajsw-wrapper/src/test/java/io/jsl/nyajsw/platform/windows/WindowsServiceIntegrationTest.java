package io.jsl.nyajsw.platform.windows;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.jsl.nyajsw.TestConfigs;
import io.jsl.nyajsw.config.WrapperConfig;
import io.jsl.nyajsw.platform.ServiceBody;
import io.jsl.nyajsw.platform.ServiceContext;
import io.jsl.nyajsw.platform.ServiceControl;
import io.jsl.nyajsw.platform.ServiceException;
import io.jsl.nyajsw.platform.ServiceExit;
import io.jsl.nyajsw.platform.ServiceState;
import io.jsl.nyajsw.platform.ServiceStatus;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Install, query and remove a throwaway service against the real Service Control Manager (plan
 * P5.6), and read what was registered back from the registry, where the Service Control Manager
 * keeps it ({@code HKLM\SYSTEM\CurrentControlSet\Services\<name>}): only this test sees whether the
 * native calls stored what the configuration says. Excluded by default; run with {@code mvn -q
 * verify -Pwindows-integration} in an elevated shell. Starting and stopping the service are in
 * {@link WindowsServiceEndToEndTest}.
 *
 * <p>Each test gets a {@link ThrowawayService}, removed after it also when it fails.
 */
@Test(groups = "windows-integration")
public class WindowsServiceIntegrationTest {

    private static final int SERVICE_WIN32_OWN_PROCESS = 0x10;
    private static final int SERVICE_AUTO_START = 2;
    private static final int SERVICE_DEMAND_START = 3;

    private ThrowawayService service;
    private Path dir;
    private String name;
    private ServiceCommandLine commandLine;
    private WindowsServiceController controller;

    @BeforeMethod(alwaysRun = true)
    public void setUp() throws IOException {
        service = null; // one instance runs every method: nothing may be left from the one before
        service = ThrowawayService.create();
        dir = service.dir;
        name = service.name;
        commandLine = service.commandLine;
        controller = service.controller;
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws InterruptedException {
        if (service != null) {
            service.close();
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
