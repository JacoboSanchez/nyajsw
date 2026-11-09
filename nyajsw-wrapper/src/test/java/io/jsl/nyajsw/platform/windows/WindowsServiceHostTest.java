package io.jsl.nyajsw.platform.windows;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.jsl.nyajsw.platform.PlatformException;
import io.jsl.nyajsw.platform.ServiceBody;
import io.jsl.nyajsw.platform.ServiceContext;
import io.jsl.nyajsw.platform.ServiceControl;
import io.jsl.nyajsw.platform.ServiceException;
import io.jsl.nyajsw.platform.ServiceExit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

import org.testng.annotations.Test;

public class WindowsServiceHostTest {

    private static final int STOPPED = ScmApi.SERVICE_STOPPED;
    private static final int START_PENDING = ScmApi.SERVICE_START_PENDING;
    private static final int STOP_PENDING = ScmApi.SERVICE_STOP_PENDING;
    private static final int RUNNING = ScmApi.SERVICE_RUNNING;
    private static final int ACCEPTS = ServiceHostApi.SERVICE_ACCEPT_STOP | ServiceHostApi.SERVICE_ACCEPT_PRESHUTDOWN
            | ServiceHostApi.SERVICE_ACCEPT_SHUTDOWN;

    private static StatusReport report(int state, int accepts, int checkPoint, Duration waitHint) {
        return new StatusReport(state, accepts, 0, 0, checkPoint, waitHint);
    }

    private static StatusReport stopped(int win32, int specific) {
        return new StatusReport(STOPPED, 0, win32, specific, 0, Duration.ZERO);
    }

    /**
     * A service that reports a start in two steps, runs until it is asked to stop, reports one
     * step of stopping and ends with {@code exit}; what it was told is recorded.
     */
    private static final class Body implements ServiceBody {

        final List<ServiceControl> stops = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch stopped = new CountDownLatch(1);
        volatile String stopThread;
        private final ServiceExit exit;
        private final Consumer<ServiceContext> afterStop;
        volatile String name;

        Body(ServiceExit exit, Consumer<ServiceContext> afterStop) {
            this.exit = exit;
            this.afterStop = afterStop;
        }

        Body() {
            this(ServiceExit.SUCCESS, context -> { });
        }

        @Override
        public ServiceExit run(ServiceContext context) {
            name = context.serviceName();
            context.starting(Duration.ofSeconds(5));
            context.running();
            try {
                assertTrue(stopped.await(10, TimeUnit.SECONDS), "no stop came");
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            afterStop.accept(context);
            context.stopping(Duration.ofSeconds(3));
            return exit;
        }

        @Override
        public void stop(ServiceControl control) {
            stops.add(control);
            stopThread = Thread.currentThread().getName();
            stopped.countDown();
        }
    }

    @Test
    public void aServiceStartsRunsAndStopsWithEveryStepReported() {
        FakeServiceHostApi api = new FakeServiceHostApi("svc").sendWhenRunning(ServiceHostApi.SERVICE_CONTROL_STOP);
        Body body = new Body();

        new WindowsServiceHost(api).run(body);

        assertEquals(api.reports, List.of(
                report(START_PENDING, 0, 1, WindowsServiceHost.FIRST_WAIT_HINT),
                report(START_PENDING, 0, 2, Duration.ofSeconds(5)),
                report(RUNNING, ACCEPTS, 0, Duration.ZERO),
                report(STOP_PENDING, 0, 1, WindowsServiceHost.FIRST_WAIT_HINT),
                report(STOP_PENDING, 0, 2, Duration.ofSeconds(3)),
                stopped(0, 0)));
        assertEquals(api.answers, List.of(ServiceHostApi.NO_ERROR));
        assertEquals(body.stops, List.of(ServiceControl.STOP));
        assertEquals(api.registeredName, "svc");
        assertEquals(body.name, "svc");
        assertEquals(api.serviceThread, "fake-service-main",
                "the handler is registered on the service main's thread (Windows names it nyajsw-service)");
        assertEquals(body.stopThread, WindowsServiceHost.DISPATCHER_THREAD, "controls come on the dispatcher's thread");
    }

    @Test
    public void aStartReportAfterRunningIsIgnored() {
        FakeServiceHostApi api = new FakeServiceHostApi("svc").sendWhenRunning(ServiceHostApi.SERVICE_CONTROL_STOP);
        new WindowsServiceHost(api).run(new ServiceBody() {
            private final CountDownLatch stop = new CountDownLatch(1);

            @Override
            public ServiceExit run(ServiceContext context) {
                context.running();
                context.starting(Duration.ofSeconds(5)); // would make the service refuse every control
                try {
                    assertTrue(stop.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return ServiceExit.SUCCESS;
            }

            @Override
            public void stop(ServiceControl control) {
                stop.countDown();
            }
        });

        List<Integer> states = api.reports.stream().map(StatusReport::currentState).toList();
        assertEquals(states, List.of(START_PENDING, RUNNING, STOP_PENDING, STOPPED));
    }

    @Test
    public void aVeryShortWaitHintIsRaisedToTheFloor() {
        FakeServiceHostApi api = new FakeServiceHostApi("svc");
        new WindowsServiceHost(api).run(new ServiceBody() {
            @Override
            public ServiceExit run(ServiceContext context) {
                context.starting(Duration.ZERO);
                context.stopping(Duration.ofMillis(500));
                return ServiceExit.SUCCESS;
            }

            @Override
            public void stop(ServiceControl control) {
            }
        });

        assertEquals(api.reports.get(1), report(START_PENDING, 0, 2, WindowsServiceHost.MIN_WAIT_HINT));
        assertEquals(api.reports.get(2), report(STOP_PENDING, 0, 1, WindowsServiceHost.MIN_WAIT_HINT));
    }

    @Test
    public void runningAcceptsStopPreshutdownAndShutdown() {
        assertEquals(WindowsServiceHost.RUNNING_ACCEPTS, 0x1 | 0x4 | 0x100);
    }

    @Test
    public void eachShutdownControlIsAStop() {
        for (int control : List.of(ServiceHostApi.SERVICE_CONTROL_PRESHUTDOWN, ServiceHostApi.SERVICE_CONTROL_SHUTDOWN)) {
            FakeServiceHostApi api = new FakeServiceHostApi("svc").sendWhenRunning(control);
            Body body = new Body();

            new WindowsServiceHost(api).run(body);

            assertEquals(body.stops, List.of(control == ServiceHostApi.SERVICE_CONTROL_SHUTDOWN
                    ? ServiceControl.SHUTDOWN : ServiceControl.PRESHUTDOWN));
            assertEquals(api.reports.get(3), report(STOP_PENDING, 0, 1, WindowsServiceHost.FIRST_WAIT_HINT));
        }
    }

    @Test
    public void aStopIsNeverUndoneByALateStartReport() {
        FakeServiceHostApi api = new FakeServiceHostApi("svc").sendWhenRunning(ServiceHostApi.SERVICE_CONTROL_STOP);
        Body body = new Body(ServiceExit.SUCCESS, context -> {
            context.running();
            context.starting(Duration.ofSeconds(1));
        });

        new WindowsServiceHost(api).run(body);

        List<Integer> states = api.reports.stream().map(StatusReport::currentState).toList();
        assertEquals(states, List.of(START_PENDING, START_PENDING, RUNNING, STOP_PENDING, STOP_PENDING, STOPPED));
    }

    @Test
    public void interrogateIsAnsweredAndOtherControlsAreRefused() {
        FakeServiceHostApi api = new FakeServiceHostApi("svc").sendWhenRunning(ServiceHostApi.SERVICE_CONTROL_INTERROGATE,
                2 /* SERVICE_CONTROL_PAUSE */, ServiceHostApi.SERVICE_CONTROL_STOP);
        Body body = new Body();

        new WindowsServiceHost(api).run(body);

        assertEquals(api.answers, List.of(ServiceHostApi.NO_ERROR, ServiceHostApi.ERROR_CALL_NOT_IMPLEMENTED,
                ServiceHostApi.NO_ERROR));
        assertEquals(body.stops, List.of(ServiceControl.STOP));
        assertEquals(api.reports.size(), 6, api.reports.toString());
    }

    @Test
    public void aFailedServiceStopsWithItsOwnErrorCode() {
        FakeServiceHostApi api = new FakeServiceHostApi("svc").sendWhenRunning(ServiceHostApi.SERVICE_CONTROL_STOP);

        new WindowsServiceHost(api).run(new Body(new ServiceExit(7), context -> { }));

        assertEquals(api.reports.get(api.reports.size() - 1), stopped(Win32Errors.SERVICE_SPECIFIC_ERROR, 7));
    }

    @Test
    public void aServiceThatThrowsIsReportedStoppedAndTheFailureRethrown() {
        FakeServiceHostApi api = new FakeServiceHostApi("svc");
        IllegalStateException failure = new IllegalStateException("boom");

        IllegalStateException thrown = expectThrows(IllegalStateException.class,
                () -> new WindowsServiceHost(api).run(new ServiceBody() {
                    @Override
                    public ServiceExit run(ServiceContext context) {
                        throw failure;
                    }

                    @Override
                    public void stop(ServiceControl control) {
                    }
                }));

        assertSame(thrown, failure);
        assertEquals(api.reports, List.of(report(START_PENDING, 0, 1, WindowsServiceHost.FIRST_WAIT_HINT),
                stopped(Win32Errors.SERVICE_SPECIFIC_ERROR, ServiceExit.UNEXPECTED_ERROR.code())));
    }

    @Test
    public void aControlAfterStoppedReachesNothing() {
        FakeServiceHostApi api = new FakeServiceHostApi("svc").sendWhenRunning(ServiceHostApi.SERVICE_CONTROL_STOP);
        Body body = new Body();
        new WindowsServiceHost(api).run(body);
        int reported = api.reports.size();

        assertEquals(api.send(ServiceHostApi.SERVICE_CONTROL_STOP), ServiceHostApi.NO_ERROR);

        assertEquals(api.reports.size(), reported);
        assertEquals(body.stops, List.of(ServiceControl.STOP));
    }

    @Test
    public void aStopThatCannotBeReportedStillReachesTheServiceAndIsRethrownAfterStopped() {
        FakeServiceHostApi api = new FakeServiceHostApi("svc").sendWhenRunning(ServiceHostApi.SERVICE_CONTROL_STOP)
                .refuse(report -> report.currentState() == STOP_PENDING
                        && report.waitHint().equals(WindowsServiceHost.FIRST_WAIT_HINT));
        Body body = new Body();

        ServiceException thrown = expectThrows(ServiceException.class, () -> new WindowsServiceHost(api).run(body));

        assertEquals(thrown.errorCode(), 6);
        assertEquals(body.stops, List.of(ServiceControl.STOP));
        assertEquals(api.reports.get(api.reports.size() - 1), stopped(0, 0));
    }

    @Test
    public void aStoppedThatCannotBeReportedEndsTheRunWithoutWaitingForTheDispatcher() {
        FakeServiceHostApi api = new FakeServiceHostApi("svc").sendWhenRunning(ServiceHostApi.SERVICE_CONTROL_STOP)
                .refuse(report -> report.currentState() == STOPPED);
        long started = System.nanoTime();

        ServiceException thrown = expectThrows(ServiceException.class, () -> new WindowsServiceHost(api).run(new Body()));

        assertEquals(thrown.errorCode(), 6);
        assertTrue(api.reports.stream().noneMatch(report -> report.currentState() == STOPPED), api.reports.toString());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0,
                "the run waited for a dispatcher that Windows would never release");
    }

    @Test
    public void theServiceFailureIsThrownWithTheControlFailuresAttached() {
        FakeServiceHostApi api = new FakeServiceHostApi("svc").sendWhenRunning(ServiceHostApi.SERVICE_CONTROL_STOP)
                .refuse(report -> report.currentState() == STOP_PENDING);
        IllegalStateException failure = new IllegalStateException("boom");

        IllegalStateException thrown = expectThrows(IllegalStateException.class,
                () -> new WindowsServiceHost(api).run(new Body(ServiceExit.SUCCESS, context -> {
                    throw failure;
                })));

        assertSame(thrown, failure);
        assertEquals(thrown.getSuppressed().length, 1);
        assertEquals(((ServiceException) thrown.getSuppressed()[0]).errorCode(), 6);
        assertEquals(api.reports.get(api.reports.size() - 1),
                stopped(Win32Errors.SERVICE_SPECIFIC_ERROR, ServiceExit.UNEXPECTED_ERROR.code()));
    }

    @Test
    public void aProcessNotStartedAsAServiceIsToldWhatToRunInstead() {
        FakeServiceHostApi api = new FakeServiceHostApi("svc").failDispatcher(JnaScmApi.failure(
                "StartServiceCtrlDispatcher", Win32Errors.FAILED_SERVICE_CONTROLLER_CONNECT));

        ServiceException thrown = expectThrows(ServiceException.class, () -> new WindowsServiceHost(api).run(new Body()));

        assertEquals(thrown.errorCode(), 1063);
        assertTrue(thrown.getMessage().startsWith("cannot run as a service: StartServiceCtrlDispatcher: "),
                thrown.getMessage());
        assertTrue(thrown.getMessage().contains("start the installed service with -t, or run the application in the"
                + " console with -c"), thrown.getMessage());
    }

    @Test
    public void aNativeLibraryThatCannotBeLoadedNamesTheTemporaryDirectory() {
        ServiceHostApi broken = new ServiceHostApi() {
            @Override
            public void runDispatcher(Consumer<String> serviceMain) {
                throw new UnsatisfiedLinkError("no jnidispatch");
            }

            @Override
            public ScmHandle registerHandler(String serviceName, IntUnaryOperator handler) {
                throw new AssertionError("not connected");
            }

            @Override
            public void setStatus(ScmHandle statusHandle, StatusReport report) {
                throw new AssertionError("not connected");
            }
        };

        PlatformException thrown = expectThrows(PlatformException.class, () -> new WindowsServiceHost(broken).run(new Body()));

        assertTrue(thrown.getMessage().startsWith("cannot run as a service: the native library (JNA) cannot be loaded"),
                thrown.getMessage());
        assertTrue(thrown.getMessage().contains("jna.tmpdir"), thrown.getMessage());
    }

    @Test
    public void aHandlerThatCannotBeRegisteredEndsTheRunWithoutWaitingForTheDispatcher() {
        FakeServiceHostApi api = new FakeServiceHostApi("svc").failRegistration(JnaScmApi.failure(
                "RegisterServiceCtrlHandlerEx", 1060));
        Body body = new Body();

        ServiceException thrown = expectThrows(ServiceException.class, () -> new WindowsServiceHost(api).run(body));

        assertEquals(thrown.errorCode(), 1060);
        assertTrue(thrown.getMessage().startsWith("cannot run the service 'svc': RegisterServiceCtrlHandlerEx: "),
                thrown.getMessage());
        assertEquals(api.reports, List.of());
        assertEquals(body.name, null, "the service did not run");
    }
}
