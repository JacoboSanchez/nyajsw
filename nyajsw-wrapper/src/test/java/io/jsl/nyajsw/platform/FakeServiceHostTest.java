package io.jsl.nyajsw.platform;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.annotations.Test;

/** The test double keeps the rules of {@link ServiceHost}, so tests built on it see what a real host does. */
public class FakeServiceHostTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test
    public void aStopFromAnotherThreadIsReportedBeforeTheServiceHearsOfItAndStoppedComesLast() throws Exception {
        FakeServiceHost host = new FakeServiceHost("svc");
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch starting = new CountDownLatch(1);
        CountDownLatch tried = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread service = new Thread(() -> host.run(new ServiceBody() {
            @Override
            public ServiceExit run(ServiceContext context) {
                assertEquals(context.serviceName(), "svc");
                context.starting(Duration.ofSeconds(4));
                starting.countDown();
                await(tried);
                context.running();
                context.starting(Duration.ofSeconds(1)); // after running: ignored
                await(asked);
                context.running(); // too late: ignored
                context.stopping(Duration.ofSeconds(2));
                return new ServiceExit(3);
            }

            @Override
            public void stop(ServiceControl control) {
                assertEquals(host.states().get(host.states().size() - 1), ServiceState.STOP_PENDING);
                asked.countDown();
            }
        }));
        assertFalse(host.stop(ServiceControl.STOP), "nothing runs yet");
        service.setUncaughtExceptionHandler((thread, e) -> failure.set(e));
        service.start();
        await(starting);
        assertFalse(host.stop(ServiceControl.STOP), "Windows refuses a stop while the service starts");
        tried.countDown();

        assertTrue(host.awaitRunning(WAIT));
        assertTrue(host.stop(ServiceControl.STOP));
        assertTrue(host.awaitStopped(WAIT));
        service.join(WAIT.toMillis());
        assertFalse(service.isAlive());
        assertEquals(failure.get(), null);

        assertEquals(host.states(), List.of(ServiceState.START_PENDING, ServiceState.START_PENDING, ServiceState.RUNNING,
                ServiceState.STOP_PENDING, ServiceState.STOP_PENDING, ServiceState.STOPPED));
        assertEquals(host.exit(), Optional.of(new ServiceExit(3)));
        assertFalse(host.stop(ServiceControl.SHUTDOWN), "it has stopped");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }
}
