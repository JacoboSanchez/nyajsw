package io.jsl.nyajsw.platform.windows;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.jsl.nyajsw.platform.ServiceException;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;

import org.testng.annotations.Test;

public class StateWaitTest {

    @Test
    public void thePollIntervalIsATenthOfTheWaitHintWithinBounds() {
        assertEquals(StateWait.interval(0), Duration.ofMillis(100));
        assertEquals(StateWait.interval(-5), Duration.ofMillis(100));
        assertEquals(StateWait.interval(3_000), Duration.ofMillis(300));
        assertEquals(StateWait.interval(60_000), Duration.ofSeconds(1));
    }

    @Test
    public void timeoutsAreShownInSecondsOrMilliseconds() {
        assertEquals(StateWait.shown(Duration.ofSeconds(30)), "30 s");
        assertEquals(StateWait.shown(Duration.ofMillis(500)), "500 ms");
        assertEquals(StateWait.shown(Duration.ofMillis(1500)), "1500 ms");
    }

    @Test
    public void withoutAGiveUpStateAStoppedServiceIsWaitedOn() {
        Iterator<Integer> states = List.of(ScmApi.SERVICE_STOPPED, ScmApi.SERVICE_RUNNING).iterator();
        StateWait wait = new StateWait(() -> 0, duration -> { });

        ScmStatus reached = wait.until(() -> new ScmStatus(states.next(), 0, 0, 0, 1), ScmApi.SERVICE_RUNNING, 0,
                Duration.ofSeconds(1));

        assertEquals(reached.currentState(), ScmApi.SERVICE_RUNNING);
    }

    @Test
    public void aStoppedServiceSaysWhyItStopped() {
        StateWait wait = new StateWait(() -> 0, duration -> { });

        ServiceException failure = expectThrows(ServiceException.class, () -> wait.until(
                () -> new ScmStatus(ScmApi.SERVICE_STOPPED, Win32Errors.SERVICE_LOGON_FAILED, 0, 0, 0),
                ScmApi.SERVICE_RUNNING, ScmApi.SERVICE_STOPPED, Duration.ofSeconds(1)));

        assertTrue(failure.getMessage().startsWith("the service stopped again (the service could not log on (Win32"
                + " error 1069)"), failure.getMessage());
        assertEquals(failure.errorCode(), 0);
    }

    @Test
    public void aServiceThatStoppedCleanlyPointsToTheLog() {
        StateWait wait = new StateWait(() -> 0, duration -> { });

        ServiceException failure = expectThrows(ServiceException.class, () -> wait.until(
                () -> new ScmStatus(ScmApi.SERVICE_STOPPED, 0, 0, 0, 0), ScmApi.SERVICE_RUNNING,
                ScmApi.SERVICE_STOPPED, Duration.ofSeconds(1)));

        assertEquals(failure.getMessage(), "the service stopped again; see the wrapper log");
    }

    @Test
    public void anInterruptedWaitKeepsTheInterruptFlag() {
        StateWait wait = new StateWait(() -> 0, duration -> {
            throw new InterruptedException();
        });

        try {
            ServiceException failure = expectThrows(ServiceException.class, () -> wait.until(
                    () -> new ScmStatus(ScmApi.SERVICE_STOP_PENDING, 0, 0, 0, 1), ScmApi.SERVICE_STOPPED, 0,
                    Duration.ofSeconds(1)));

            assertEquals(failure.getMessage(), "interrupted while waiting for the service");
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
