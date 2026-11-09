package io.jsl.nyajsw.cli;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import io.jsl.nyajsw.config.LogLevel;
import io.jsl.nyajsw.lifecycle.FakeScheduler;
import io.jsl.nyajsw.lifecycle.State;
import io.jsl.nyajsw.log.WrapperLog;
import io.jsl.nyajsw.platform.ServiceContext;
import io.jsl.nyajsw.platform.ServiceException;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ServiceStatusReporterTest {

    private static final Duration INTERVAL = Duration.ofSeconds(5);
    private static final Duration HINT = Duration.ofSeconds(10);

    private final List<String> reports = new ArrayList<>();
    private FakeScheduler scheduler;
    private ByteArrayOutputStream bytes;
    private WrapperLog log;
    private boolean refuse;

    private final ServiceContext context = new ServiceContext() {
        @Override
        public String serviceName() {
            return "svc";
        }

        @Override
        public void starting(Duration waitHint) {
            record("starting " + waitHint.toSeconds());
        }

        @Override
        public void running() {
            record("running");
        }

        @Override
        public void stopping(Duration waitHint) {
            record("stopping " + waitHint.toSeconds());
        }

        private void record(String report) {
            if (refuse) {
                throw new ServiceException("SetServiceStatus: the handle is invalid (Win32 error 6)", 6);
            }
            reports.add(report);
        }
    };

    @BeforeMethod
    public void setUp() {
        reports.clear();
        refuse = false;
        scheduler = new FakeScheduler();
        bytes = new ByteArrayOutputStream();
        log = WrapperLog.console(new PrintStream(bytes, true, StandardCharsets.UTF_8), LogLevel.INFO);
    }

    private static final Duration START_LIMIT = Duration.ofSeconds(60);
    private static final Duration STOP_LIMIT = Duration.ofSeconds(30);

    private ServiceStatusReporter reporter() {
        return new ServiceStatusReporter(context, scheduler, INTERVAL, START_LIMIT, STOP_LIMIT, log);
    }

    @Test
    public void theWaitHintIsTwiceTheReadinessIntervalAndAtLeastTwoSeconds() {
        assertEquals(ServiceStatusReporter.waitHint(Duration.ofSeconds(5)), Duration.ofSeconds(10));
        assertEquals(ServiceStatusReporter.waitHint(Duration.ofMillis(300)), Duration.ofSeconds(2));
        assertEquals(ServiceStatusReporter.waitHint(Duration.ofDays(200_000_000)),
                io.jsl.nyajsw.lifecycle.Supervisor.LONGEST_WAIT);
    }

    @Test
    public void theStartIsReportedEveryHalfWaitHintUntilTheApplicationIsReady() {
        ServiceStatusReporter reporter = reporter();

        reporter.begin();
        reporter.changed(State.IDLE, State.STARTING);
        scheduler.advance(HINT.dividedBy(2));
        scheduler.advance(HINT.dividedBy(2));
        reporter.changed(State.STARTING, State.RUNNING);
        scheduler.advance(HINT.multipliedBy(3));

        assertEquals(reports, List.of("starting 10", "starting 10", "starting 10", "running"));
    }

    @Test
    public void aRestartBeforeTheFirstReadyIsStillTheStartAndOneAfterItDoesNotShow() {
        ServiceStatusReporter reporter = reporter();
        reporter.begin();
        reporter.changed(State.STARTING, State.RESTART_WAIT);
        reporter.changed(State.RESTART_WAIT, State.STARTING);
        scheduler.advance(HINT.dividedBy(2));
        reporter.changed(State.STARTING, State.RUNNING);

        reporter.changed(State.RUNNING, State.RESTART_WAIT);
        reporter.changed(State.RESTART_WAIT, State.STARTING);
        reporter.changed(State.STARTING, State.RUNNING);
        scheduler.advance(HINT.multipliedBy(3));

        assertEquals(reports, List.of("starting 10", "starting 10", "running"));
    }

    @Test
    public void aStopIsReportedUntilSupervisionEnds() {
        ServiceStatusReporter reporter = reporter();
        reporter.begin();
        reporter.changed(State.STARTING, State.RUNNING);

        reporter.changed(State.RUNNING, State.STOPPING);
        scheduler.advance(HINT.dividedBy(2));
        reporter.changed(State.STOPPING, State.STOPPED);
        scheduler.advance(HINT.multipliedBy(3));

        assertEquals(reports, List.of("starting 10", "running", "stopping 10", "stopping 10"));
    }

    @Test
    public void aSupervisionThatFailsWhileStartingReportsNothingMore() {
        ServiceStatusReporter reporter = reporter();
        reporter.begin();

        reporter.changed(State.STARTING, State.FAILED);
        scheduler.advance(HINT.multipliedBy(3));
        reporter.changed(State.FAILED, State.RUNNING); // cannot happen; and if it did, nothing is reported

        assertEquals(reports, List.of("starting 10"));
    }

    @Test
    public void supervisionThatWaitsIdleBeforeTheFirstReadyIsARunningServiceThatCanBeStopped() {
        ServiceStatusReporter reporter = reporter();
        reporter.begin();

        reporter.changed(State.STARTING, State.IDLE); // the application ended with action STOP under LOOSE
        scheduler.advance(HINT.multipliedBy(3));
        reporter.changed(State.IDLE, State.STOPPED);

        assertEquals(reports, List.of("starting 10", "running"));
    }

    @Test
    public void aStartThatGoesOnPastItsLimitIsNoLongerReported() {
        ServiceStatusReporter reporter = reporter();
        reporter.begin();

        scheduler.advance(START_LIMIT.plus(HINT));

        assertEquals(reports.size(), (int) (START_LIMIT.toSeconds() / HINT.dividedBy(2).toSeconds()) + 1, reports.toString());
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("has been starting for longer than 60 s"),
                bytes.toString(StandardCharsets.UTF_8));
        scheduler.advance(HINT.multipliedBy(10));
        assertEquals(reports.size(), (int) (START_LIMIT.toSeconds() / HINT.dividedBy(2).toSeconds()) + 1);
    }

    @Test
    public void aStopThatGoesOnPastItsLimitIsNoLongerReported() {
        ServiceStatusReporter reporter = reporter();
        reporter.begin();
        reporter.changed(State.STARTING, State.RUNNING);
        reporter.changed(State.RUNNING, State.STOPPING);

        scheduler.advance(STOP_LIMIT.multipliedBy(3));

        assertEquals(reports.stream().filter(report -> report.startsWith("stopping")).count(),
                STOP_LIMIT.toSeconds() / HINT.dividedBy(2).toSeconds() + 1, reports.toString());
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("has been stopping for longer than 30 s"),
                bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void aRunningReportThatFailsIsTriedAgain() {
        ServiceStatusReporter reporter = reporter();
        reporter.begin();
        refuse = true;

        reporter.changed(State.STARTING, State.RUNNING);
        refuse = false;
        scheduler.advance(HINT.dividedBy(2));
        scheduler.advance(HINT.multipliedBy(3));

        assertEquals(reports, List.of("starting 10", "running"));
    }

    @Test
    public void aReportThatFailsIsLoggedOnceAndTheRepeatsGoOn() {
        ServiceStatusReporter reporter = reporter();
        refuse = true;

        reporter.begin();
        scheduler.advance(HINT.dividedBy(2));
        refuse = false;
        scheduler.advance(HINT.dividedBy(2));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(output.lines().filter(line -> line.contains("cannot report the service's state")).count(), 1L,
                output);
        assertTrue(output.contains("(Win32 error 6)"), output);
        assertEquals(reports, List.of("starting 10"));
        reporter.end();
    }
}
