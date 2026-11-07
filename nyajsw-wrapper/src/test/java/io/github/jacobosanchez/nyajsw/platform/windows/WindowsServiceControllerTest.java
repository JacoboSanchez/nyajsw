package io.github.jacobosanchez.nyajsw.platform.windows;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.TestConfigs;
import io.github.jacobosanchez.nyajsw.config.ConfigException;
import io.github.jacobosanchez.nyajsw.config.Secret;
import io.github.jacobosanchez.nyajsw.config.StartType;
import io.github.jacobosanchez.nyajsw.config.WrapperConfig;
import io.github.jacobosanchez.nyajsw.platform.ServiceException;
import io.github.jacobosanchez.nyajsw.platform.ServiceState;
import io.github.jacobosanchez.nyajsw.platform.ServiceStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class WindowsServiceControllerTest {

    private static final ScmHandle MANAGER = new ScmHandle(1);
    private static final ScmHandle SERVICE = new ScmHandle(2);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private Path dir;
    private ScmApi scm;
    private ServiceCommandLine commandLine;
    private WindowsServiceController controller;
    private long nanos;

    @BeforeMethod
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("nyajsw-scm").toRealPath();
        scm = mock(ScmApi.class);
        commandLine = new ServiceCommandLine(dir.resolve("jdk"), List.of(dir.resolve("nyajsw.jar")), StandardCharsets.UTF_8);
        nanos = 0;
        controller = new WindowsServiceController(scm, commandLine, new StateWait(() -> nanos,
                duration -> nanos += duration.toNanos()));
        when(scm.openManager(anyInt())).thenReturn(MANAGER);
        when(scm.openService(eq(MANAGER), anyString(), anyInt())).thenReturn(SERVICE);
        when(scm.createService(eq(MANAGER), any(), anyInt())).thenReturn(SERVICE);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws IOException {
        TestConfigs.delete(dir);
    }

    private static ServiceException error(int code) {
        return new ServiceException("Call: " + Win32Errors.describe(code, ""), code);
    }

    private static ScmStatus status(int state) {
        return new ScmStatus(state, 0, 0, 0, state == ScmApi.SERVICE_STOPPED ? 0 : 4242);
    }

    @Test
    public void installRegistersTheServiceAsConfiguredAndClosesTheHandles() throws IOException {
        WrapperConfig config = TestConfigs.load(dir, "wrapper.ntservice.name=svc", "wrapper.ntservice.displayname=My service",
                "wrapper.ntservice.description=Runs it", "wrapper.ntservice.starttype=DELAYED_AUTO_START",
                "wrapper.ntservice.dependency.1=Tcpip", "wrapper.ntservice.dependency.2=Afd",
                "wrapper.ntservice.account=.\\svcuser", "wrapper.ntservice.password=hunter2",
                "wrapper.shutdown.timeout=30", "wrapper.jvm_exit.timeout=15");

        assertEquals(controller.install(config), List.of());

        ArgumentCaptor<ServiceRegistration> registration = ArgumentCaptor.forClass(ServiceRegistration.class);
        InOrder order = inOrder(scm);
        order.verify(scm).openManager(ScmApi.SC_MANAGER_CREATE_SERVICE);
        order.verify(scm).createService(eq(MANAGER), registration.capture(),
                eq(ScmApi.SERVICE_CHANGE_CONFIG | ScmApi.DELETE));
        order.verify(scm).setDescription(SERVICE, "Runs it");
        order.verify(scm).setDelayedAutoStart(SERVICE, true);
        order.verify(scm).setPreshutdownTimeout(SERVICE, Duration.ofSeconds(30 + 15 + 10 + 10));
        order.verify(scm).close(SERVICE);
        order.verify(scm).close(MANAGER);
        ServiceRegistration registered = registration.getValue();
        assertEquals(registered.name(), "svc");
        assertEquals(registered.displayName(), "My service");
        assertEquals(registered.binaryPath(), commandLine.build(config).binaryPath());
        assertEquals(registered.startType(), StartType.DELAYED_AUTO_START);
        assertEquals(registered.dependencies(), List.of("Tcpip", "Afd"));
        assertEquals(registered.account(), Optional.of(".\\svcuser"));
        assertEquals(registered.password().map(Secret::reveal), Optional.of("hunter2"));
    }

    @Test
    public void aPlainServiceIsNamedAfterItsMainClassAndHasNoDescriptionOrDelay() throws IOException {
        controller.install(TestConfigs.load(dir, "wrapper.java.app.mainclass=com.example.Server"));

        ArgumentCaptor<ServiceRegistration> registration = ArgumentCaptor.forClass(ServiceRegistration.class);
        verify(scm).createService(eq(MANAGER), registration.capture(), anyInt());
        assertEquals(registration.getValue().name(), "Server");
        assertEquals(registration.getValue().displayName(), "Server");
        verify(scm, never()).setDescription(any(), any());
        verify(scm, never()).setDelayedAutoStart(any(), eq(true));
        verify(scm).setPreshutdownTimeout(eq(SERVICE), any());
    }

    @Test
    public void theCommandLinesWarningsAreReturned() throws IOException {
        List<String> warnings = controller.install(TestConfigs.load(dir, "wrapper.ntservice.name=svc",
                "wrapper.ntservice.additional.1=\"\""));

        assertEquals(warnings, List.of("an empty wrapper.ntservice.additional.<n> is skipped"));
    }

    @Test
    public void aServiceWithoutANameIsAConfigurationError() throws IOException {
        WrapperConfig config = TestConfigs.load(dir, "wrapper.java.app.mainclass=", "wrapper.java.app.jar=app.jar");

        ConfigException failure = expectThrows(ConfigException.class, () -> controller.install(config));

        assertTrue(failure.getMessage().contains("wrapper.ntservice.name is not set"), failure.getMessage());
        verify(scm, never()).openManager(anyInt());
    }

    @Test
    public void aServiceThatCannotBeConfiguredIsDeletedAgain() throws IOException {
        doThrow(error(Win32Errors.ACCESS_DENIED)).when(scm).setPreshutdownTimeout(eq(SERVICE), any());

        ServiceException failure = expectThrows(ServiceException.class,
                () -> controller.install(TestConfigs.load(dir, "wrapper.ntservice.name=svc")));

        assertEquals(failure.getMessage(), "cannot install the service 'svc': Call: access is denied (Win32 error 5);"
                + " run the command as Administrator");
        assertEquals(failure.errorCode(), Win32Errors.ACCESS_DENIED);
        InOrder order = inOrder(scm);
        order.verify(scm).deleteService(SERVICE);
        order.verify(scm).close(SERVICE);
        order.verify(scm).close(MANAGER);
    }

    @Test
    public void aDescriptionThatCannotBeSetAlsoDeletesTheService() throws IOException {
        doThrow(error(Win32Errors.ACCESS_DENIED)).when(scm).setDescription(SERVICE, "Runs it");

        expectThrows(ServiceException.class, () -> controller.install(TestConfigs.load(dir, "wrapper.ntservice.name=svc",
                "wrapper.ntservice.description=Runs it")));

        verify(scm).deleteService(SERVICE);
        verify(scm, never()).setPreshutdownTimeout(any(), any());
    }

    @Test
    public void aDeleteThatFailsDuringTheRollbackIsKeptWithTheFirstFailure() throws IOException {
        doThrow(error(Win32Errors.ACCESS_DENIED)).when(scm).setDelayedAutoStart(SERVICE, true);
        doThrow(error(Win32Errors.SERVICE_MARKED_FOR_DELETE)).when(scm).deleteService(SERVICE);

        ServiceException failure = expectThrows(ServiceException.class, () -> controller.install(TestConfigs.load(dir,
                "wrapper.ntservice.name=svc", "wrapper.ntservice.starttype=DELAYED_AUTO_START")));

        assertEquals(failure.errorCode(), Win32Errors.ACCESS_DENIED);
        assertEquals(failure.getCause().getSuppressed().length, 1);
        verify(scm).close(SERVICE);
        verify(scm).close(MANAGER);
    }

    @Test
    public void anyOtherFailureAfterTheCreateAlsoDeletesTheService() throws IOException {
        doThrow(new IllegalStateException("structure")).when(scm).setPreshutdownTimeout(eq(SERVICE), any());

        expectThrows(IllegalStateException.class,
                () -> controller.install(TestConfigs.load(dir, "wrapper.ntservice.name=svc")));

        verify(scm).deleteService(SERVICE);
        verify(scm).close(SERVICE);
    }

    @Test
    public void aFailedCreateSaysWhatWasAttemptedAndClosesTheManager() throws IOException {
        when(scm.createService(eq(MANAGER), any(), anyInt())).thenThrow(error(Win32Errors.SERVICE_EXISTS));

        ServiceException failure = expectThrows(ServiceException.class,
                () -> controller.install(TestConfigs.load(dir, "wrapper.ntservice.name=svc")));

        assertTrue(failure.getMessage().startsWith("cannot install the service 'svc': Call: a service of that name"
                + " is installed already (Win32 error 1073); remove it first (-r)"), failure.getMessage());
        assertEquals(failure.errorCode(), Win32Errors.SERVICE_EXISTS);
        verify(scm).close(MANAGER);
        verify(scm, never()).close(SERVICE);
    }

    @Test
    public void removeDeletesTheServiceWithDeleteAccessOnly() {
        controller.remove("svc");

        InOrder order = inOrder(scm);
        order.verify(scm).openManager(ScmApi.SC_MANAGER_CONNECT);
        order.verify(scm).openService(MANAGER, "svc", ScmApi.DELETE);
        order.verify(scm).deleteService(SERVICE);
        order.verify(scm).close(SERVICE);
        order.verify(scm).close(MANAGER);
    }

    @Test
    public void aRemoveThatFailsStillClosesBothHandles() {
        doThrow(error(Win32Errors.SERVICE_MARKED_FOR_DELETE)).when(scm).deleteService(SERVICE);

        ServiceException failure = expectThrows(ServiceException.class, () -> controller.remove("svc"));

        assertTrue(failure.getMessage().startsWith("cannot remove the service 'svc': "), failure.getMessage());
        verify(scm).close(SERVICE);
        verify(scm).close(MANAGER);
    }

    @Test
    public void startWaitsUntilTheServiceRuns() {
        when(scm.queryStatus(SERVICE)).thenReturn(status(ScmApi.SERVICE_START_PENDING),
                status(ScmApi.SERVICE_START_PENDING), status(ScmApi.SERVICE_RUNNING));

        controller.start("svc", TIMEOUT);

        verify(scm).openService(MANAGER, "svc", ScmApi.SERVICE_START | ScmApi.SERVICE_QUERY_STATUS);
        verify(scm).startService(SERVICE);
        verify(scm).close(SERVICE);
        verify(scm).close(MANAGER);
        assertEquals(nanos, 2 * StateWait.SHORTEST_POLL.toNanos());
    }

    @Test
    public void aServiceThatStopsWhileStartingIsAFailureWithItsExitCode() {
        when(scm.queryStatus(SERVICE)).thenReturn(status(ScmApi.SERVICE_START_PENDING),
                new ScmStatus(ScmApi.SERVICE_STOPPED, Win32Errors.SERVICE_SPECIFIC_ERROR, 7, 0, 0));

        ServiceException failure = expectThrows(ServiceException.class, () -> controller.start("svc", TIMEOUT));

        assertEquals(failure.getMessage(), "cannot start the service 'svc': the service stopped again (its own exit"
                + " code 7; see the wrapper log)");
        verify(scm).close(SERVICE);
    }

    @Test
    public void aServiceStillStartingAfterTheTimeoutIsAFailure() {
        when(scm.queryStatus(SERVICE)).thenReturn(new ScmStatus(ScmApi.SERVICE_START_PENDING, 0, 0, 20_000, 4242));

        ServiceException failure = expectThrows(ServiceException.class,
                () -> controller.start("svc", Duration.ofSeconds(5)));

        assertEquals(failure.getMessage(), "cannot start the service 'svc': the service is still start pending"
                + " after 5 s");
        assertEquals(nanos, Duration.ofSeconds(5).toNanos(), "polled every second, the most a wait hint gives");
    }

    @Test
    public void startingARunningServiceIsARefusal() {
        doThrow(error(Win32Errors.SERVICE_ALREADY_RUNNING)).when(scm).startService(SERVICE);

        ServiceException failure = expectThrows(ServiceException.class, () -> controller.start("svc", TIMEOUT));

        assertEquals(failure.getMessage(), "cannot start the service 'svc': Call: the service is already running"
                + " (Win32 error 1056)");
        verify(scm, never()).queryStatus(any());
    }

    @Test
    public void stopWaitsUntilTheServiceHasStopped() {
        when(scm.queryStatus(SERVICE)).thenReturn(status(ScmApi.SERVICE_STOP_PENDING), status(ScmApi.SERVICE_STOPPED));

        controller.stop("svc", TIMEOUT);

        verify(scm).openService(MANAGER, "svc", ScmApi.SERVICE_STOP | ScmApi.SERVICE_QUERY_STATUS);
        verify(scm).stopService(SERVICE);
        verify(scm).close(SERVICE);
    }

    @Test
    public void aServiceStillStoppingAfterTheTimeoutIsAFailure() {
        when(scm.queryStatus(SERVICE)).thenReturn(status(ScmApi.SERVICE_STOP_PENDING));

        ServiceException failure = expectThrows(ServiceException.class,
                () -> controller.stop("svc", Duration.ofMillis(250)));

        assertEquals(failure.getMessage(), "cannot stop the service 'svc': the service is still stop pending after"
                + " 250 ms");
        assertEquals(nanos, Duration.ofMillis(250).toNanos(), "the last pause ends at the deadline");
        verify(scm).close(SERVICE);
    }

    @Test
    public void stoppingAStoppedServiceIsARefusal() {
        doThrow(error(Win32Errors.SERVICE_NOT_ACTIVE)).when(scm).stopService(SERVICE);

        ServiceException failure = expectThrows(ServiceException.class, () -> controller.stop("svc", TIMEOUT));

        assertEquals(failure.errorCode(), Win32Errors.SERVICE_NOT_ACTIVE);
        assertTrue(failure.getMessage().startsWith("cannot stop the service 'svc': "), failure.getMessage());
    }

    @Test
    public void statusGivesTheStateAndProcessWithQueryAccessOnly() {
        when(scm.queryStatus(SERVICE)).thenReturn(status(ScmApi.SERVICE_RUNNING));

        assertEquals(controller.status("svc"), Optional.of(new ServiceStatus(ServiceState.RUNNING, OptionalLong.of(4242))));
        verify(scm).openManager(ScmApi.SC_MANAGER_CONNECT);
        verify(scm).openService(MANAGER, "svc", ScmApi.SERVICE_QUERY_STATUS);
        verify(scm).close(SERVICE);
        verify(scm).close(MANAGER);
    }

    @Test
    public void aStoppedServiceHasNoProcess() {
        when(scm.queryStatus(SERVICE)).thenReturn(status(ScmApi.SERVICE_STOPPED));

        assertEquals(controller.status("svc"), Optional.of(new ServiceStatus(ServiceState.STOPPED, OptionalLong.empty())));
    }

    @Test
    public void aServiceThatIsNotInstalledHasNoStatus() {
        when(scm.openService(MANAGER, "svc", ScmApi.SERVICE_QUERY_STATUS))
                .thenThrow(error(Win32Errors.SERVICE_DOES_NOT_EXIST));

        assertFalse(controller.status("svc").isPresent());
        verify(scm).close(MANAGER);
    }

    @Test
    public void aServiceThatCannotBeOpenedForAQuerySaysWhy() {
        when(scm.openService(MANAGER, "svc", ScmApi.SERVICE_QUERY_STATUS)).thenThrow(error(Win32Errors.ACCESS_DENIED));

        ServiceException failure = expectThrows(ServiceException.class, () -> controller.status("svc"));

        assertTrue(failure.getMessage().startsWith("cannot query the service 'svc': Call: access is denied"),
                failure.getMessage());
        verify(scm).close(MANAGER);
    }

    @Test
    public void anUnknownStateIsAFailure() {
        when(scm.queryStatus(SERVICE)).thenReturn(status(99));

        ServiceException failure = expectThrows(ServiceException.class, () -> controller.status("svc"));

        assertEquals(failure.getMessage(), "cannot query the service 'svc': the Service Control Manager reports an"
                + " unknown service state 99");
        verify(scm).close(SERVICE);
    }

    @Test
    public void aNativeLibraryThatCannotBeLoadedIsAReadableFailure() {
        when(scm.openManager(anyInt())).thenThrow(new UnsatisfiedLinkError("Unable to load library 'jnidispatch'"));

        io.github.jacobosanchez.nyajsw.platform.PlatformException failure = expectThrows(
                io.github.jacobosanchez.nyajsw.platform.PlatformException.class, () -> controller.status("svc"));

        assertTrue(failure.getMessage().startsWith("cannot query the service 'svc': the native library (JNA) cannot"
                + " be loaded: java.lang.UnsatisfiedLinkError: Unable to load library 'jnidispatch'; check that the"
                + " directory '"), failure.getMessage());
    }

    @Test
    public void aManagerThatCannotBeOpenedSaysWhy() {
        when(scm.openManager(anyInt())).thenThrow(error(Win32Errors.ACCESS_DENIED));

        ServiceException failure = expectThrows(ServiceException.class, () -> controller.remove("svc"));

        assertTrue(failure.getMessage().startsWith("cannot remove the service 'svc': Call: access is denied"),
                failure.getMessage());
        verify(scm, never()).close(any());
    }
}
