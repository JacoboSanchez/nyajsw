package io.github.jacobosanchez.nyajsw.cli;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import io.github.jacobosanchez.nyajsw.config.ConfigException;
import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ConfigLoader;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.lifecycle.Supervisor;
import io.github.jacobosanchez.nyajsw.platform.Platform;
import io.github.jacobosanchez.nyajsw.platform.PlatformException;
import io.github.jacobosanchez.nyajsw.platform.ServiceController;
import io.github.jacobosanchez.nyajsw.platform.ServiceException;
import io.github.jacobosanchez.nyajsw.platform.ServiceState;
import io.github.jacobosanchez.nyajsw.platform.ServiceStatus;
import io.github.jacobosanchez.nyajsw.platform.unsupported.UnsupportedPlatform;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The service commands through {@link Main}, against a mocked {@link ServiceController}. */
public class ServiceCommandsTest {

    private Path dir;
    private ByteArrayOutputStream bytes;
    private PrintStream out;
    private ServiceController services;
    private Platform platform;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-svc-cli").toRealPath();
        bytes = new ByteArrayOutputStream();
        out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        services = mock(ServiceController.class);
        platform = mock(Platform.class);
        when(platform.serviceController()).thenReturn(services);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private String output() {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private String conf(String... lines) throws IOException {
        List<String> all = new ArrayList<>(List.of("wrapper.java.app.mainclass=com.example.Server",
                "wrapper.ntservice.name=svc"));
        all.addAll(List.of(lines));
        return Files.write(dir.resolve("wrapper.conf"), all).toString();
    }

    private int run(String command, String conf) {
        return Main.run(new String[] {command, conf}, out, () -> platform);
    }

    @Test
    public void installRegistersTheServiceAndReportsItsWarnings() throws IOException {
        when(services.install(any())).thenReturn(List.of("an empty wrapper.ntservice.additional.<n> is skipped"));

        assertEquals(run("-i", conf()), ExitCode.SUCCESS.code(), output());

        verify(services).install(any(WrapperConfig.class));
        assertTrue(output().contains("warning: an empty wrapper.ntservice.additional.<n> is skipped"), output());
        assertTrue(output().contains("installed the service 'svc'"), output());
    }

    @Test
    public void eachSwitchAndItsLongFormRunsItsCommand() throws IOException {
        String conf = conf();
        for (String command : List.of("-r", "--remove")) {
            assertEquals(run(command, conf), 0, output());
        }
        for (String command : List.of("-t", "--start", "-p", "--stop", "--install", "-q", "--query")) {
            assertEquals(run(command, conf), 0, output());
        }

        verify(services, times(2)).remove("svc");
        verify(services, times(2)).start("svc", Duration.ofSeconds(30 + 30));
        verify(services, times(2)).stop(any(), any());
        verify(services).install(any());
        verify(services, times(2)).status("svc");
        assertTrue(output().contains("removed the service 'svc'"), output());
        assertTrue(output().contains("started the service 'svc'"), output());
        assertTrue(output().contains("stopped the service 'svc'"), output());
    }

    @Test
    public void aQueryTellsTheStateAndProcess() {
        assertEquals(ServiceCommands.describe("svc", Optional.of(new ServiceStatus(ServiceState.RUNNING,
                OptionalLong.of(4242)))), "the service 'svc' is running (pid 4242)");
        assertEquals(ServiceCommands.describe("svc", Optional.of(new ServiceStatus(ServiceState.START_PENDING,
                OptionalLong.empty()))), "the service 'svc' is start pending");
        assertEquals(ServiceCommands.describe("svc", Optional.empty()), "the service 'svc' is not installed");
    }

    @Test
    public void aServiceThatIsNotInstalledIsAnAnswerToAQuery() throws IOException {
        when(services.status("svc")).thenReturn(Optional.empty());

        assertEquals(run("-q", conf()), ExitCode.SUCCESS.code());
        assertTrue(output().contains("the service 'svc' is not installed"), output());
    }

    @Test
    public void theTimeoutsFollowTheConfiguration() throws IOException {
        WrapperConfig config = load(conf("wrapper.startup.timeout=90", "wrapper.shutdown.timeout=40",
                "wrapper.jvm_exit.timeout=5"));

        assertEquals(ServiceCommands.startTimeout(config), Duration.ofSeconds(90 + 30));
        assertEquals(ServiceCommands.stopTimeout(config), Duration.ofSeconds(40 + 5 + 10 + 10));
        assertEquals(ServiceCommands.startTimeout(load(conf("wrapper.startup.timeout=0"))), ServiceCommands.NO_LIMIT);
    }

    @Test
    public void theStartWaitsThroughTheStartupDelay() throws IOException {
        WrapperConfig config = load(conf("wrapper.startup.delay=60"));

        assertEquals(ServiceCommands.startTimeout(config), Duration.ofSeconds(60 + 30 + 30));
    }

    @Test
    public void hugeTimeoutsAreCutToTheLongestWait() throws IOException {
        WrapperConfig config = load(conf("wrapper.startup.timeout=9223372036854775807",
                "wrapper.shutdown.timeout=9223372036854775807"));

        assertEquals(ServiceCommands.startTimeout(config), Supervisor.LONGEST_WAIT);
        assertEquals(ServiceCommands.stopTimeout(config), Supervisor.LONGEST_WAIT);
    }

    private static WrapperConfig load(String conf) throws IOException {
        return WrapperConfig.from(new ConfigLoader(Map.of(), Map.of(), false, ConfigKeys.registry()).load(Path.of(conf)));
    }

    @Test
    public void aRefusalIsReportedMaskedWithExitCodeThree() throws IOException {
        doThrow(new ServiceException("cannot start the service 'svc': logon with hunter2 failed", 1069))
                .when(services).start(any(), any());

        assertEquals(run("-t", conf("wrapper.ntservice.password=hunter2")), ExitCode.SERVICE_OPERATION_FAILED.code());

        assertTrue(output().contains("cannot start the service 'svc': logon with **** failed"), output());
        assertFalse(output().contains("hunter2"), output());
    }

    @Test
    public void aConfigurationProblemFoundByACommandIsShownMasked() throws IOException {
        when(services.install(any())).thenThrow(new ConfigException("cannot use hunter2 here"));

        assertEquals(run("-i", conf("wrapper.ntservice.password=hunter2")), ExitCode.CONFIGURATION_ERROR.code());

        assertTrue(output().contains("cannot use **** here"), output());
        assertFalse(output().contains("hunter2"), output());
    }

    @Test
    public void anOperatingSystemFailureEndsWithExitCodeOne() throws IOException {
        when(services.status("svc")).thenThrow(new PlatformException("cannot query the service 'svc': no JNA"));

        assertEquals(run("-q", conf()), ExitCode.UNEXPECTED_ERROR.code());
        assertTrue(output().contains("cannot query the service 'svc': no JNA"), output());
    }

    @Test
    public void builtInAndVirtualAccountsNeedNoLogonRight() throws IOException {
        for (String account : List.of("LocalSystem", ".\\\\localsystem", "NT AUTHORITY\\\\LocalService",
                "networkservice", "NT AUTHORITY\\\\SYSTEM", "NT AUTHORITY\\\\Network Service",
                "NT SERVICE\\\\svc")) {
            assertFalse(load(conf("wrapper.ntservice.account=" + account)).service().needsServiceLogonRight(), account);
        }
        assertFalse(load(conf()).service().needsServiceLogonRight());
        for (String account : List.of("DOMAIN\\\\svc", ".\\\\svcuser", "DOMAIN\\\\gmsa$")) {
            assertTrue(load(conf("wrapper.ntservice.account=" + account)).service().needsServiceLogonRight(), account);
        }
    }

    @Test
    public void theTemporaryPathBecomesJnaTmpdirUnlessItIsSet() throws IOException {
        WrapperConfig config = load(conf("wrapper.tmp.path=tmp/../jna"));
        Properties empty = new Properties();
        Properties set = new Properties();
        set.setProperty("jna.tmpdir", "C:/given");

        Main.useTmpPath(config, empty);
        Main.useTmpPath(config, set);

        assertEquals(empty.getProperty("jna.tmpdir"), dir.resolve("jna").toString());
        assertEquals(set.getProperty("jna.tmpdir"), "C:/given");
    }

    @Test
    public void aSecretInTheServiceNameIsMaskedInEveryResult() throws IOException {
        String conf = conf("wrapper.ntservice.password=hunter2",
                "wrapper.ntservice.name=svc-${wrapper.ntservice.password}");
        when(services.install(any())).thenReturn(List.of("about svc-hunter2"));
        when(services.status("svc-hunter2")).thenReturn(Optional.empty());

        for (String command : List.of("-i", "-r", "-t", "-p", "-q")) {
            assertEquals(run(command, conf), 0, output());
        }

        // The real name still reaches the service manager.
        verify(services).remove("svc-hunter2");
        verify(services).start(eq("svc-hunter2"), any());
        verify(services).stop(eq("svc-hunter2"), any());
        assertTrue(output().contains("installed the service 'svc-****'"), output());
        assertTrue(output().contains("the service 'svc-****' is not installed"), output());
        assertTrue(output().contains("warning: about svc-****"), output());
        assertFalse(output().contains("hunter2"), output());
    }

    @Test
    public void aHostWithoutServicesEndsWithExitCodeFour() throws IOException {
        int code = Main.run(new String[] {"-i", conf()}, out, () -> new UnsupportedPlatform("Linux"));

        assertEquals(code, ExitCode.UNSUPPORTED_PLATFORM.code());
        assertTrue(output().contains("cannot install a service: nyajsw runs services on Windows only"), output());
    }

    @Test
    public void aServiceWithoutANameIsAConfigurationError() throws IOException {
        String conf = Files.write(dir.resolve("wrapper.conf"), List.of("wrapper.java.app.jar=app.jar")).toString();

        assertEquals(run("-q", conf), ExitCode.CONFIGURATION_ERROR.code());

        assertTrue(output().contains("wrapper.ntservice.name is not set"), output());
        verifyNoInteractions(services);
    }

    @Test
    public void aConfigurationThatCannotBeLoadedIsAConfigurationError() {
        assertEquals(run("-i", dir.resolve("missing.conf").toString()), ExitCode.CONFIGURATION_ERROR.code());
        verifyNoInteractions(platform);
    }

    @Test
    public void theCommandsWriteNoLogFile() throws IOException {
        assertEquals(run("-q", conf()), 0);

        assertFalse(Files.exists(dir.resolve("wrapper.log")), "a command beside a running service must not roll its log");
    }
}
