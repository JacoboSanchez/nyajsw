package io.jsl.nyajsw.lifecycle;

import static io.jsl.nyajsw.lifecycle.State.FAILED;
import static io.jsl.nyajsw.lifecycle.State.IDLE;
import static io.jsl.nyajsw.lifecycle.State.RESTART_WAIT;
import static io.jsl.nyajsw.lifecycle.State.RUNNING;
import static io.jsl.nyajsw.lifecycle.State.STARTING;
import static io.jsl.nyajsw.lifecycle.State.STOPPED;
import static io.jsl.nyajsw.lifecycle.State.STOPPING;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.jsl.nyajsw.config.ControlMode;
import io.jsl.nyajsw.config.ExitAction;
import io.jsl.nyajsw.config.FailureAction;
import io.jsl.nyajsw.config.LifecycleConfig;
import io.jsl.nyajsw.config.LogLevel;
import io.jsl.nyajsw.launch.ApplicationLauncher;
import io.jsl.nyajsw.launch.LaunchException;
import io.jsl.nyajsw.log.WrapperLog;
import io.jsl.nyajsw.probe.Probe;
import io.jsl.nyajsw.probe.ProbeContext;
import io.jsl.nyajsw.probe.ProbeRegistry;
import io.jsl.nyajsw.probe.ProbeResult;
import io.jsl.nyajsw.probe.ProbeRunner;
import io.jsl.nyajsw.probe.ProcessProbe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class SupervisorTest {

    @Test
    public void aStopIsWaitedForThroughItsBudgetTheTerminationAndTheMargin() {
        assertEquals(Supervisor.stopWait(Duration.ofSeconds(45)), Duration.ofSeconds(45 + 10 + 10));
        assertEquals(Supervisor.stopWait(Duration.ofSeconds(Long.MAX_VALUE)), Supervisor.LONGEST_WAIT);
        assertEquals(Supervisor.sum(Duration.ofDays(200_000), Duration.ofDays(1)), Supervisor.LONGEST_WAIT);
        assertEquals(Supervisor.sum(), Duration.ZERO);
    }

    private static final Duration RESTART_DELAY = Lifecycles.RESTART_DELAY;
    private static final Duration SHUTDOWN_TIMEOUT = Lifecycles.SHUTDOWN_TIMEOUT;
    private static final Duration SUCCESSFUL_TIME = Lifecycles.SUCCESSFUL_TIME;
    private static final int MAX_FAILED = Lifecycles.MAX_FAILED;
    private static final Duration READINESS_INTERVAL = Duration.ofSeconds(1);
    private static final Duration STARTUP_TIMEOUT = Lifecycles.STARTUP_TIMEOUT;

    private FakeScheduler scheduler;
    private List<FakeApplication> launched;
    private List<String> transitions;
    private ByteArrayOutputStream logBytes;
    private WrapperLog log;
    /** The default readiness: the process probe, checked every second. */
    private ProbeRunner readiness;
    /** The default stop: no graceful strategy, then terminate. */
    private StopChain stops;

    @BeforeMethod
    public void setUp() {
        stops = new StopChain(List.of(), new StopChain.Step(TerminateStopStrategy.NAME, new TerminateStopStrategy()),
                List.of());
        readiness = new ProbeRunner(List.of(new ProbeRegistry.NamedProbe(ProcessProbe.TYPE, new ProcessProbe())),
                READINESS_INTERVAL);
        scheduler = new FakeScheduler();
        launched = new CopyOnWriteArrayList<>();
        transitions = new CopyOnWriteArrayList<>();
        logBytes = new ByteArrayOutputStream();
        log = WrapperLog.console(new PrintStream(logBytes, true, StandardCharsets.UTF_8), LogLevel.DEBUG);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        log.close();
    }

    private static LifecycleConfig lifecycle(ExitAction defaultAction, Map<Integer, ExitAction> actions) {
        return lifecycle(ControlMode.TIGHT, defaultAction, actions, MAX_FAILED, Duration.ZERO);
    }

    private static LifecycleConfig lifecycle(ControlMode control, ExitAction defaultAction,
            Map<Integer, ExitAction> actions, int maxFailedInvocations, Duration startupDelay) {
        return lifecycle(control, defaultAction, actions, maxFailedInvocations, startupDelay, STARTUP_TIMEOUT,
                Duration.ZERO);
    }

    private static LifecycleConfig lifecycle(ControlMode control, ExitAction defaultAction,
            Map<Integer, ExitAction> actions, int maxFailedInvocations, Duration startupDelay, Duration startupTimeout,
            Duration jvmExitTimeout) {
        return Lifecycles.lifecycle(control, defaultAction, actions, maxFailedInvocations, startupDelay,
                startupTimeout, jvmExitTimeout, List.of(TerminateStopStrategy.NAME));
    }

    private Supervisor supervisor(LifecycleConfig lifecycle) {
        return supervisor(lifecycle, this::launch);
    }

    private Supervisor supervisor(LifecycleConfig lifecycle, ApplicationLauncher launcher) {
        return new Supervisor(lifecycle, launcher, readiness, Liveness.NONE, Runnable::run, stops, scheduler, log,
                (from, to) -> transitions.add(from + "->" + to));
    }

    private FakeApplication launch(int invocation) {
        FakeApplication application = new FakeApplication(invocation);
        launched.add(application);
        return application;
    }

    /** Every exit action under every {@code wrapper.control}, and the state that follows the exit (D20). */
    @DataProvider
    public Object[][] exitActions() {
        return new Object[][] {
                {ControlMode.TIGHT, ExitAction.RESTART, RESTART_WAIT},
                {ControlMode.TIGHT, ExitAction.SHUTDOWN, STOPPED},
                {ControlMode.TIGHT, ExitAction.STOP, STOPPED},
                {ControlMode.WRAPPER, ExitAction.RESTART, RESTART_WAIT},
                {ControlMode.WRAPPER, ExitAction.SHUTDOWN, STOPPED},
                {ControlMode.WRAPPER, ExitAction.STOP, STOPPED},
                {ControlMode.APPLICATION, ExitAction.RESTART, RESTART_WAIT},
                {ControlMode.APPLICATION, ExitAction.SHUTDOWN, STOPPED},
                {ControlMode.APPLICATION, ExitAction.STOP, IDLE},
                {ControlMode.LOOSE, ExitAction.RESTART, RESTART_WAIT},
                {ControlMode.LOOSE, ExitAction.SHUTDOWN, STOPPED},
                {ControlMode.LOOSE, ExitAction.STOP, IDLE},
        };
    }

    @Test(dataProvider = "exitActions")
    public void anExitWhileRunningMovesAsItsActionAndTheControlModeSay(ControlMode control, ExitAction action,
            State expected) {
        Supervisor supervisor = running(lifecycle(control, ExitAction.SHUTDOWN, Map.of(4, action), MAX_FAILED,
                Duration.ZERO));

        launched.get(0).exit(4);
        supervisor.runPending();

        assertEquals(supervisor.state(), expected);
    }

    @DataProvider
    public Object[][] actions() {
        return new Object[][] {{ExitAction.RESTART}, {ExitAction.SHUTDOWN}, {ExitAction.STOP}};
    }

    @Test(dataProvider = "actions")
    public void anExitWhileStoppingIgnoresItsAction(ExitAction action) {
        // APPLICATION: a stop stops the application, and STOP alone would keep the wrapper waiting.
        Supervisor supervisor = running(lifecycle(ControlMode.APPLICATION, ExitAction.SHUTDOWN, Map.of(4, action),
                MAX_FAILED, Duration.ZERO));

        supervisor.requestStop();
        supervisor.runPending();
        launched.get(0).exit(4);
        supervisor.runPending();

        assertEquals(supervisor.state(), STOPPED);
        assertEquals(scheduler.pending(), 0, "no restart or shutdown timer is left");
    }

    private Supervisor running(LifecycleConfig lifecycle) {
        Supervisor supervisor = supervisor(lifecycle);
        supervisor.start();
        supervisor.runPending();
        assertEquals(supervisor.state(), RUNNING);
        return supervisor;
    }

    private void advance(Supervisor supervisor, Duration by) {
        scheduler.advance(by);
        supervisor.runPending();
    }

    private String logged() {
        return logBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void startLaunchesTheFirstInvocationAndCountsItAsReady() {
        running(lifecycle(ExitAction.SHUTDOWN, Map.of()));

        assertEquals(launched.size(), 1);
        assertEquals(launched.get(0).name(), "jvm 1");
        assertEquals(transitions, List.of("IDLE->STARTING", "STARTING->RUNNING"));
    }

    @Test
    public void shutdownActionEndsSupervisionWithTheExitCode() {
        Supervisor supervisor = running(lifecycle(ExitAction.SHUTDOWN, Map.of()));

        launched.get(0).exit(3);
        supervisor.runPending();

        assertEquals(supervisor.outcome(), new Supervisor.Outcome(STOPPED, OptionalInt.of(3)));
        assertTrue(logged().contains("jvm 1 exit code 3 means SHUTDOWN"), logged());
    }

    @Test
    public void restartActionStartsTheNextInvocationAfterTheRestartDelay() {
        Supervisor supervisor = running(lifecycle(ExitAction.SHUTDOWN, Map.of(3, ExitAction.RESTART)));

        launched.get(0).exit(3);
        supervisor.runPending();
        assertEquals(supervisor.state(), RESTART_WAIT);

        advance(supervisor, RESTART_DELAY.minusMillis(1));
        assertEquals(supervisor.state(), RESTART_WAIT);
        assertEquals(launched.size(), 1);

        advance(supervisor, Duration.ofMillis(1));
        assertEquals(supervisor.state(), RUNNING);
        assertEquals(launched.size(), 2);
        assertEquals(launched.get(1).name(), "jvm 2");
        assertEquals(transitions, List.of("IDLE->STARTING", "STARTING->RUNNING", "RUNNING->RESTART_WAIT",
                "RESTART_WAIT->STARTING", "STARTING->RUNNING"));
    }

    @Test
    public void exitCodesWithoutTheirOwnActionTakeTheDefault() {
        Supervisor supervisor = running(lifecycle(ExitAction.RESTART, Map.of(0, ExitAction.SHUTDOWN)));

        launched.get(0).exit(7);
        supervisor.runPending();
        assertEquals(supervisor.state(), RESTART_WAIT);

        advance(supervisor, RESTART_DELAY);
        launched.get(1).exit(0);
        supervisor.runPending();
        assertEquals(supervisor.outcome(), new Supervisor.Outcome(STOPPED, OptionalInt.of(0)));
    }

    @Test
    public void stopActionKeepsTheWrapperIdleUntilItIsStopped() {
        Supervisor supervisor = running(lifecycle(ControlMode.LOOSE, ExitAction.SHUTDOWN, Map.of(0, ExitAction.STOP),
                MAX_FAILED, Duration.ZERO));

        launched.get(0).exit(0);
        supervisor.runPending();
        assertEquals(supervisor.state(), IDLE);
        advance(supervisor, Duration.ofHours(1));
        assertEquals(supervisor.state(), IDLE);
        assertEquals(launched.size(), 1);

        supervisor.requestStop();
        supervisor.runPending();
        assertEquals(supervisor.outcome(), new Supervisor.Outcome(STOPPED, OptionalInt.of(0)));
    }

    @Test
    public void stopWaitsTheShutdownTimeoutAndThenTerminatesTheProcessTree() {
        Supervisor supervisor = running(lifecycle(ExitAction.SHUTDOWN, Map.of()));
        FakeApplication application = launched.get(0);

        supervisor.requestStop();
        supervisor.runPending();
        assertEquals(supervisor.state(), STOPPING);

        advance(supervisor, SHUTDOWN_TIMEOUT.minusMillis(1));
        assertEquals(application.terminations(), 0);
        assertEquals(supervisor.state(), STOPPING);

        advance(supervisor, Duration.ofMillis(1));
        assertEquals(application.terminations(), 1);
        assertEquals(supervisor.outcome(), new Supervisor.Outcome(STOPPED, OptionalInt.of(1)));
        assertTrue(logged().contains("jvm 1 did not end within 30 s; terminating it (terminate)"), logged());
    }

    @Test
    public void noExitActionAppliesWhileStopping() {
        Supervisor supervisor = running(lifecycle(ExitAction.RESTART, Map.of()));
        FakeApplication application = launched.get(0);

        supervisor.requestStop();
        supervisor.runPending();
        application.exit(1);
        supervisor.runPending();

        assertEquals(supervisor.outcome(), new Supervisor.Outcome(STOPPED, OptionalInt.of(1)));
        advance(supervisor, SHUTDOWN_TIMEOUT.plus(RESTART_DELAY).plus(Supervisor.AFTER_TERMINATION));
        assertEquals(launched.size(), 1);
        assertEquals(application.terminations(), 0, "the shutdown timer no longer counts");
        assertEquals(transitions, List.of("IDLE->STARTING", "STARTING->RUNNING", "RUNNING->STOPPING",
                "STOPPING->STOPPED"));
        assertTrue(!logged().contains("means RESTART"), logged());
    }

    @Test
    public void anApplicationThatSurvivesTerminationIsGivenUp() {
        FakeApplication stubborn = new FakeApplication(1).survivingTermination();
        Supervisor supervisor = supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), invocation -> stubborn);
        supervisor.start();
        supervisor.runPending();

        supervisor.requestStop();
        supervisor.runPending();
        advance(supervisor, SHUTDOWN_TIMEOUT);
        assertEquals(stubborn.terminations(), 1);
        assertEquals(supervisor.state(), STOPPING);

        advance(supervisor, Supervisor.AFTER_TERMINATION);
        assertEquals(supervisor.outcome(), new Supervisor.Outcome(FAILED, OptionalInt.empty()));
        assertTrue(logged().contains("jvm 1 is still running 10 s after it was terminated; giving up on it"),
                logged());

        stubborn.exit(9); // too late to matter
        supervisor.runPending();
        assertEquals(supervisor.outcome(), new Supervisor.Outcome(FAILED, OptionalInt.empty()));
    }

    @Test
    public void stopDuringTheRestartDelayCancelsTheRestart() {
        Supervisor supervisor = running(lifecycle(ExitAction.RESTART, Map.of()));

        launched.get(0).exit(2);
        supervisor.runPending();
        supervisor.requestStop();
        supervisor.runPending();
        assertEquals(supervisor.outcome(), new Supervisor.Outcome(STOPPED, OptionalInt.of(2)));

        advance(supervisor, RESTART_DELAY);
        assertEquals(launched.size(), 1);
    }

    @Test
    public void stopBeforeTheStartStartsNothing() {
        Supervisor supervisor = supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()));

        supervisor.requestStop();
        supervisor.start();
        supervisor.runPending();

        assertEquals(supervisor.outcome(), new Supervisor.Outcome(STOPPED, OptionalInt.empty()));
        assertEquals(launched.size(), 0);
        assertEquals(transitions, List.of("IDLE->STOPPED"));
    }

    @Test
    public void aSecondStopRequestChangesNothing() {
        Supervisor supervisor = running(lifecycle(ExitAction.SHUTDOWN, Map.of()));

        supervisor.requestStop();
        supervisor.runPending();
        advance(supervisor, Duration.ofSeconds(20));
        supervisor.requestStop();
        supervisor.runPending();

        advance(supervisor, Duration.ofSeconds(10));
        assertEquals(launched.get(0).terminations(), 1, "the first stop's timer still counts");
        assertEquals(supervisor.state(), STOPPED);
    }

    @Test
    public void aLaunchErrorIsRetriedLikeAFailedInvocationWhateverTheExitActions() {
        List<Integer> attempts = new ArrayList<>();
        Supervisor supervisor = supervisor(lifecycle(ControlMode.TIGHT, ExitAction.SHUTDOWN, Map.of(), 3,
                Duration.ZERO), invocation -> {
                    attempts.add(invocation);
                    throw new LaunchException("the Java executable C:/missing/java does not exist");
                });

        supervisor.start();
        supervisor.runPending();
        assertEquals(supervisor.state(), RESTART_WAIT);
        advance(supervisor, RESTART_DELAY);
        advance(supervisor, RESTART_DELAY);

        assertEquals(attempts, List.of(1, 2, 3));
        assertEquals(supervisor.outcome(), new Supervisor.Outcome(FAILED, OptionalInt.empty()));
        assertEquals(transitions, List.of("IDLE->STARTING", "STARTING->RESTART_WAIT", "RESTART_WAIT->STARTING",
                "STARTING->RESTART_WAIT", "RESTART_WAIT->STARTING", "STARTING->FAILED"));
        assertTrue(logged().contains("cannot start jvm 1: the Java executable C:/missing/java does not exist"),
                logged());
        assertTrue(logged().contains("giving up after jvm 3: 3 failed invocation(s) in a row reach"
                + " wrapper.max_failed_invocations"), logged());
    }

    @Test
    public void launchErrorsAndQuickExitsCountTogether() {
        Supervisor supervisor = supervisor(lifecycle(ControlMode.TIGHT, ExitAction.RESTART, Map.of(), 3,
                Duration.ZERO), invocation -> {
                    if (invocation == 2) {
                        throw new LaunchException("cannot start jvm 2");
                    }
                    return launch(invocation);
                });
        supervisor.start();
        supervisor.runPending();

        launched.get(0).exit(1); // failure 1
        supervisor.runPending();
        advance(supervisor, RESTART_DELAY); // jvm 2 cannot start: failure 2
        assertEquals(supervisor.state(), RESTART_WAIT);
        advance(supervisor, RESTART_DELAY);
        launched.get(1).exit(1); // jvm 3: failure 3
        supervisor.runPending();

        assertEquals(supervisor.outcome(), new Supervisor.Outcome(FAILED, OptionalInt.of(1)));
    }

    @Test
    public void aLaunchErrorAfterTheStartupDelayIsRetriedWithoutWaitingItAgain() {
        List<Duration> attempts = new ArrayList<>();
        Supervisor supervisor = supervisor(lifecycle(ControlMode.TIGHT, ExitAction.SHUTDOWN, Map.of(), 2,
                Duration.ofSeconds(8)), invocation -> {
                    attempts.add(scheduler.now());
                    throw new LaunchException("cannot start jvm " + invocation);
                });
        supervisor.start();
        supervisor.runPending();

        advance(supervisor, Duration.ofSeconds(8));
        advance(supervisor, RESTART_DELAY);

        assertEquals(attempts, List.of(Duration.ofSeconds(8), Duration.ofSeconds(8).plus(RESTART_DELAY)));
        assertEquals(supervisor.state(), FAILED);
    }

    @Test
    public void stopActionUnderTightLogsWhyTheWrapperEnds() {
        Supervisor supervisor = running(lifecycle(ExitAction.SHUTDOWN, Map.of(0, ExitAction.STOP)));

        launched.get(0).exit(0);
        supervisor.runPending();

        assertEquals(supervisor.state(), STOPPED);
        assertTrue(logged().contains("STOP ends the wrapper as well: wrapper.control=TIGHT ends the wrapper with"
                + " the application"), logged());
    }

    @Test
    public void stopActionUnderApplicationWaitsUntilTheWrapperIsStopped() {
        Supervisor supervisor = running(lifecycle(ControlMode.APPLICATION, ExitAction.STOP, Map.of(), MAX_FAILED,
                Duration.ZERO));

        launched.get(0).exit(2);
        supervisor.runPending();
        assertEquals(supervisor.state(), IDLE);
        supervisor.requestStop();
        supervisor.runPending();

        assertEquals(supervisor.outcome(), new Supervisor.Outcome(STOPPED, OptionalInt.of(2)));
        assertEquals(transitions.get(transitions.size() - 1), "IDLE->STOPPED");
    }

    @Test
    public void quickFailuresInARowEndSupervisionAtTheLimit() {
        Supervisor supervisor = running(lifecycle(ControlMode.TIGHT, ExitAction.RESTART, Map.of(), 3,
                Duration.ZERO));

        for (int i = 0; i < 2; i++) {
            advance(supervisor, SUCCESSFUL_TIME.minusMillis(1));
            launched.get(i).exit(1);
            supervisor.runPending();
            assertEquals(supervisor.state(), RESTART_WAIT, "failure " + (i + 1));
            advance(supervisor, RESTART_DELAY);
        }
        launched.get(2).exit(1);
        supervisor.runPending();

        assertEquals(supervisor.outcome(), new Supervisor.Outcome(FAILED, OptionalInt.of(1)));
        assertEquals(launched.size(), 3);
    }

    @Test
    public void theRunTimeEndsWhenTheProcessEndsNotWhenItsOutputIsDrained() {
        Supervisor supervisor = running(lifecycle(ControlMode.TIGHT, ExitAction.RESTART, Map.of(), 1,
                Duration.ZERO));

        advance(supervisor, SUCCESSFUL_TIME.minusSeconds(1));
        launched.get(0).endProcess(); // a grandchild keeps the output open
        supervisor.runPending();
        advance(supervisor, Duration.ofSeconds(2)); // the exit is reported after the 60 s mark
        launched.get(0).exit(1);
        supervisor.runPending();

        assertEquals(supervisor.state(), FAILED, "59 s of run time is a failure, however late the exit came");
        assertEquals(launched.size(), 1);
    }

    @Test
    public void anExitReportedBeforeTheProcessEndCountsTheRunTimeToTheExit() {
        Supervisor supervisor = running(lifecycle(ControlMode.TIGHT, ExitAction.RESTART, Map.of(), 1,
                Duration.ZERO));

        advance(supervisor, SUCCESSFUL_TIME);
        launched.get(0).reportExitOnly(1);
        supervisor.runPending();

        assertEquals(supervisor.state(), RESTART_WAIT, "60 s is a success, so the limit of 1 is not reached");
    }

    @Test
    public void aLateProcessEndOfAnEarlierInvocationDoesNotCountForTheNext() {
        Supervisor supervisor = running(lifecycle(ControlMode.TIGHT, ExitAction.RESTART, Map.of(), 2,
                Duration.ZERO));
        launched.get(0).reportExitOnly(1); // failure 1 of 2; its process end comes later
        supervisor.runPending();
        advance(supervisor, RESTART_DELAY);

        launched.get(0).endProcess(); // jvm 1's, during jvm 2
        supervisor.runPending();
        advance(supervisor, SUCCESSFUL_TIME);
        launched.get(1).exit(1);
        supervisor.runPending();

        assertEquals(supervisor.state(), RESTART_WAIT, "jvm 2 ran 60 s, a success; jvm 1's end time is not its own");
        assertTrue(logged().contains("failed invocations in a row: 0 of 2"), logged());
    }

    @Test
    public void aSuccessfulInvocationResetsTheCount() {
        Supervisor supervisor = running(lifecycle(ControlMode.TIGHT, ExitAction.RESTART, Map.of(), 2,
                Duration.ZERO));

        launched.get(0).exit(1); // failure 1 of 2
        supervisor.runPending();
        advance(supervisor, RESTART_DELAY);
        advance(supervisor, SUCCESSFUL_TIME);
        launched.get(1).exit(1); // ran long enough: the count starts again
        supervisor.runPending();
        advance(supervisor, RESTART_DELAY);
        launched.get(2).exit(1); // failure 1 of 2 again
        supervisor.runPending();

        assertEquals(supervisor.state(), RESTART_WAIT);
        assertTrue(logged().contains("failed invocations in a row: 0 of 2"), logged());
    }

    @Test
    public void withALimitOfOneTheFirstQuickFailureEndsSupervision() {
        Supervisor supervisor = running(lifecycle(ControlMode.TIGHT, ExitAction.RESTART, Map.of(), 1,
                Duration.ZERO));

        launched.get(0).exit(1);
        supervisor.runPending();

        assertEquals(supervisor.state(), FAILED);
        assertEquals(launched.size(), 1);
    }

    @Test
    public void theFirstStartWaitsTheStartupDelay() {
        Supervisor supervisor = supervisor(lifecycle(ControlMode.TIGHT, ExitAction.SHUTDOWN, Map.of(), MAX_FAILED,
                Duration.ofSeconds(8)));
        supervisor.start();
        supervisor.runPending();
        assertEquals(supervisor.state(), IDLE);

        advance(supervisor, Duration.ofSeconds(8).minusMillis(1));
        assertEquals(launched.size(), 0);
        advance(supervisor, Duration.ofMillis(1));

        assertEquals(supervisor.state(), RUNNING);
        assertTrue(logged().contains("starting in 8 s (wrapper.startup.delay)"), logged());
    }

    @Test
    public void aRestartDoesNotWaitTheStartupDelay() {
        Supervisor supervisor = supervisor(lifecycle(ControlMode.TIGHT, ExitAction.RESTART, Map.of(), MAX_FAILED,
                Duration.ofSeconds(8)));
        supervisor.start();
        supervisor.runPending();
        advance(supervisor, Duration.ofSeconds(8));

        launched.get(0).exit(1);
        supervisor.runPending();
        advance(supervisor, RESTART_DELAY);

        assertEquals(launched.size(), 2);
    }

    @Test
    public void aStopDuringTheStartupDelayStartsNothing() {
        Supervisor supervisor = supervisor(lifecycle(ControlMode.TIGHT, ExitAction.SHUTDOWN, Map.of(), MAX_FAILED,
                Duration.ofSeconds(8)));
        supervisor.start();
        supervisor.runPending();

        supervisor.requestStop();
        supervisor.runPending();
        advance(supervisor, Duration.ofSeconds(8));

        assertEquals(supervisor.outcome(), new Supervisor.Outcome(STOPPED, OptionalInt.empty()));
        assertEquals(launched.size(), 0);
    }

    @DataProvider
    public Object[][] controlModes() {
        return new Object[][] {
                {ControlMode.TIGHT, true},
                {ControlMode.APPLICATION, true},
                {ControlMode.WRAPPER, false},
                {ControlMode.LOOSE, false},
        };
    }

    @Test(dataProvider = "controlModes")
    public void aStopOfTheWrapperStopsTheApplicationOnlyWhenTheControlModeTiesThem(ControlMode control,
            boolean stopsApplication) {
        Supervisor supervisor = running(lifecycle(control, ExitAction.SHUTDOWN, Map.of(), MAX_FAILED,
                Duration.ZERO));

        supervisor.requestStop();
        supervisor.runPending();
        advance(supervisor, SHUTDOWN_TIMEOUT);

        assertEquals(supervisor.state(), STOPPED);
        assertEquals(launched.get(0).terminations(), stopsApplication ? 1 : 0);
        assertEquals(launched.get(0).isAlive(), !stopsApplication);
        if (!stopsApplication) {
            assertTrue(logged().contains("stopping the wrapper only: jvm 1 (pid 1001) keeps running"
                    + " (wrapper.control=" + control + ")"), logged());
        }
    }

    @Test
    public void anExitThatCannotBeObservedFailsSupervision() {
        Supervisor supervisor = running(lifecycle(ExitAction.RESTART, Map.of()));

        launched.get(0).loseTrack(new IllegalStateException("reaper gone"));
        supervisor.runPending();

        assertEquals(supervisor.outcome(), new Supervisor.Outcome(FAILED, OptionalInt.empty()));
        assertTrue(logged().contains("lost track of jvm 1"), logged());
    }

    @Test
    public void anExitThatCannotBeObservedWhileStoppingEndsTheStop() {
        Supervisor supervisor = running(lifecycle(ExitAction.SHUTDOWN, Map.of()));

        supervisor.requestStop();
        supervisor.runPending();
        launched.get(0).loseTrack(new IllegalStateException("reaper gone"));
        supervisor.runPending();

        assertEquals(supervisor.outcome(), new Supervisor.Outcome(STOPPED, OptionalInt.empty()));
    }

    @Test
    public void aLaunchErrorOnARestartFailsWithoutTheEarlierExitCode() {
        Supervisor supervisor = supervisor(lifecycle(ControlMode.TIGHT, ExitAction.RESTART, Map.of(), 2,
                Duration.ZERO), invocation -> {
            if (invocation > 1) {
                throw new LaunchException("cannot start jvm " + invocation);
            }
            return launch(invocation);
        });
        supervisor.start();
        supervisor.runPending();

        launched.get(0).exit(3);
        supervisor.runPending();
        advance(supervisor, RESTART_DELAY);

        assertEquals(supervisor.outcome(), new Supervisor.Outcome(FAILED, OptionalInt.empty()));
    }

    @Test
    public void anUnexpectedLaunchErrorFailsSupervision() {
        Supervisor supervisor = supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), invocation -> {
            throw new UncheckedIOException(new IOException("disk gone"));
        });

        supervisor.start();
        supervisor.runPending();

        assertEquals(supervisor.state(), FAILED);
        assertTrue(logged().contains("supervision failed unexpectedly in state STARTING"), logged());
    }

    @Test
    public void anUnexpectedErrorOnARestartFailsSupervision() {
        Supervisor supervisor = supervisor(lifecycle(ExitAction.RESTART, Map.of()), invocation -> {
            if (invocation > 1) {
                throw new IllegalStateException("broken launcher");
            }
            return launch(invocation);
        });
        supervisor.start();
        supervisor.runPending();
        launched.get(0).exit(3);
        supervisor.runPending();

        advance(supervisor, RESTART_DELAY);

        assertEquals(supervisor.state(), FAILED);
    }

    @Test
    public void anApplicationWhoseExitCannotBeWatchedIsTerminated() {
        FakeApplication unwatchable = new FakeApplication(1).failingOnExit();
        Supervisor supervisor = supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), invocation -> unwatchable);

        supervisor.start();
        supervisor.runPending();

        assertEquals(supervisor.state(), FAILED);
        assertEquals(unwatchable.terminations(), 1);
    }

    @Test
    public void aTerminationThatThrowsFailsSupervision() {
        FakeApplication broken = new FakeApplication(1).failingTermination();
        Supervisor supervisor = supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), invocation -> broken);
        supervisor.start();
        supervisor.runPending();
        supervisor.requestStop();
        supervisor.runPending();

        advance(supervisor, SHUTDOWN_TIMEOUT);

        assertEquals(supervisor.state(), FAILED);
        assertEquals(broken.terminations(), 2, "the stop's attempt, then the failure's");
        assertTrue(logged().contains("cannot terminate jvm 1"), logged());
        assertEquals(scheduler.pending(), 0);
    }

    @Test
    public void aLostExitOfAProcessStillAliveTerminatesIt() {
        Supervisor supervisor = running(lifecycle(ExitAction.SHUTDOWN, Map.of()));
        FakeApplication application = launched.get(0).survivingTermination();

        application.loseTrack(new IllegalStateException("reaper gone"));
        supervisor.runPending();

        assertEquals(supervisor.state(), FAILED);
        assertEquals(application.terminations(), 1);
    }

    @Test
    public void aStopRequestAfterTerminationLetsTheGiveUpTimerCount() {
        FakeApplication stubborn = new FakeApplication(1).survivingTermination();
        Supervisor supervisor = supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), invocation -> stubborn);
        supervisor.start();
        supervisor.runPending();
        supervisor.requestStop();
        supervisor.runPending();
        advance(supervisor, SHUTDOWN_TIMEOUT);

        supervisor.requestStop();
        supervisor.runPending();
        advance(supervisor, Supervisor.AFTER_TERMINATION);

        assertEquals(supervisor.state(), FAILED);
        assertEquals(stubborn.terminations(), 1);
    }

    @Test
    public void aFailingListenerIsLoggedAndSupervisionGoesOn() {
        Supervisor supervisor = new Supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), this::launch, readiness,
                Liveness.NONE, Runnable::run, stops, scheduler, log, (from, to) -> {
            throw new IllegalStateException("listener broke");
        });

        supervisor.start();
        supervisor.runPending();
        launched.get(0).exit(0);
        supervisor.runPending();

        assertEquals(supervisor.state(), STOPPED);
        assertTrue(logged().contains("a state listener failed on IDLE -> STARTING"), logged());
    }

    /** Makes readiness depend on {@code ready}; the probe reports {@code not yet} until then. */
    private void readinessWhen(AtomicBoolean ready) {
        readiness = new ProbeRunner(List.of(new ProbeRegistry.NamedProbe("custom",
                context -> ready.get() ? ProbeResult.pass("answers") : ProbeResult.fail("not yet"))),
                READINESS_INTERVAL);
    }

    private Supervisor starting(LifecycleConfig lifecycle) {
        Supervisor supervisor = supervisor(lifecycle);
        supervisor.start();
        supervisor.runPending();
        assertEquals(supervisor.state(), STARTING);
        return supervisor;
    }

    @Test
    public void theApplicationIsRunningOnlyOnceTheProbesPass() {
        AtomicBoolean ready = new AtomicBoolean();
        readinessWhen(ready);
        Supervisor supervisor = starting(lifecycle(ExitAction.SHUTDOWN, Map.of()));

        advance(supervisor, READINESS_INTERVAL.multipliedBy(3));
        assertEquals(supervisor.state(), STARTING);

        ready.set(true);
        advance(supervisor, READINESS_INTERVAL);
        assertEquals(supervisor.state(), RUNNING);
        assertTrue(logged().contains("jvm 1 is ready (custom)"), logged());
        assertEquals(scheduler.pending(), 0, "neither the startup timeout nor another check is left");
    }

    @Test
    public void anApplicationNotReadyWithinTheStartupTimeoutIsTerminatedAndRestartedAfterItEnded() {
        readinessWhen(new AtomicBoolean());
        Supervisor supervisor = starting(lifecycle(ExitAction.SHUTDOWN, Map.of()));
        FakeApplication unready = launched.get(0).survivingTermination();

        advance(supervisor, STARTUP_TIMEOUT.minusMillis(1));
        assertEquals(supervisor.state(), STARTING);
        advance(supervisor, Duration.ofMillis(1));
        assertEquals(supervisor.state(), RESTART_WAIT);
        assertEquals(unready.terminations(), 1, "terminated at once, not after wrapper.shutdown.timeout");
        assertTrue(logged().contains("jvm 1 did not become ready within 30 s (wrapper.startup.timeout); last check:"
                + " custom: not yet; terminating it"), logged());

        advance(supervisor, RESTART_DELAY);
        assertEquals(launched.size(), 1, "no restart before the terminated invocation has ended");
        unready.exit(1);
        supervisor.runPending();
        assertFalse(logged().contains("exit code 1 means"), "no exit action: the wrapper ended it");
        advance(supervisor, RESTART_DELAY.minusMillis(1));
        assertEquals(launched.size(), 1);
        advance(supervisor, Duration.ofMillis(1));

        assertEquals(supervisor.state(), STARTING);
        assertEquals(launched.size(), 2);
        assertEquals(transitions, List.of("IDLE->STARTING", "STARTING->RESTART_WAIT", "RESTART_WAIT->STARTING"),
                "never STOPPING, which a service host would report as a stop");
    }

    @Test
    public void notBecomingReadyIsAFailureEvenWhenTheStartupTimeoutExceedsTheSuccessfulTime() {
        readinessWhen(new AtomicBoolean());
        Duration longStartup = SUCCESSFUL_TIME.plusSeconds(30);
        Supervisor supervisor = starting(lifecycle(ControlMode.TIGHT, ExitAction.SHUTDOWN, Map.of(), 1,
                Duration.ZERO, longStartup, Duration.ZERO));

        advance(supervisor, longStartup);

        assertEquals(supervisor.state(), FAILED);
        assertEquals(launched.get(0).terminations(), 1);
        assertTrue(logged().contains("does not become ready"), logged());
    }

    @Test
    public void aStartupTimeoutOfZeroMeansNoLimit() {
        AtomicBoolean ready = new AtomicBoolean();
        readinessWhen(ready);
        Supervisor supervisor = starting(lifecycle(ControlMode.TIGHT, ExitAction.SHUTDOWN, Map.of(), MAX_FAILED,
                Duration.ZERO, Duration.ZERO, Duration.ZERO));

        advance(supervisor, Duration.ofHours(2));
        assertEquals(supervisor.state(), STARTING);
        ready.set(true);
        advance(supervisor, READINESS_INTERVAL);

        assertEquals(supervisor.state(), RUNNING);
    }

    @Test
    public void aLateResultOfAnEarlierInvocationIsIgnored() {
        List<Runnable> checks = new ArrayList<>();
        AtomicBoolean ready = new AtomicBoolean();
        readinessWhen(ready);
        Supervisor supervisor = new Supervisor(lifecycle(ExitAction.RESTART, Map.of()), this::launch, readiness,
                Liveness.NONE, checks::add, stops, scheduler, log, (from, to) -> transitions.add(from + "->" + to));
        supervisor.start();
        supervisor.runPending();
        Runnable firstCheck = checks.get(0); // jvm 1's check, still pending

        launched.get(0).exit(1);
        supervisor.runPending();
        advance(supervisor, RESTART_DELAY);
        assertEquals(supervisor.state(), STARTING);
        ready.set(true);
        firstCheck.run(); // passes, but for jvm 1
        supervisor.runPending();

        assertEquals(supervisor.state(), STARTING, "jvm 2 has not been checked yet");
    }

    @Test
    public void aLostExitOfATerminatedUnreadyInvocationFailsSupervision() {
        readinessWhen(new AtomicBoolean());
        Supervisor supervisor = starting(lifecycle(ExitAction.SHUTDOWN, Map.of()));
        FakeApplication unready = launched.get(0).survivingTermination();
        advance(supervisor, STARTUP_TIMEOUT);

        unready.loseTrack(new IllegalStateException("reaper gone"));
        supervisor.runPending();

        assertEquals(supervisor.state(), FAILED);
    }

    @Test
    public void aProbeExecutorThatRejectsALaterCheckFailsSupervision() {
        readinessWhen(new AtomicBoolean());
        AtomicBoolean shutDown = new AtomicBoolean();
        Supervisor supervisor = new Supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), this::launch, readiness,
                Liveness.NONE, task -> {
                    if (shutDown.get()) {
                        throw new RejectedExecutionException("shut down");
                    }
                    task.run();
                }, stops, scheduler, log, (from, to) -> { });
        supervisor.start();
        supervisor.runPending();

        shutDown.set(true);
        advance(supervisor, READINESS_INTERVAL);

        assertEquals(supervisor.state(), FAILED);
        assertEquals(launched.get(0).terminations(), 1);
    }

    @Test(dataProvider = "actions")
    public void anExitWhileStartingAppliesItsAction(ExitAction action) {
        readinessWhen(new AtomicBoolean());
        Supervisor supervisor = starting(lifecycle(ControlMode.APPLICATION, ExitAction.SHUTDOWN, Map.of(3, action),
                MAX_FAILED, Duration.ZERO));

        launched.get(0).exit(3);
        supervisor.runPending();

        State expected = switch (action) {
            case RESTART -> RESTART_WAIT;
            case SHUTDOWN -> STOPPED;
            case STOP -> IDLE;
        };
        assertEquals(supervisor.state(), expected);
        assertEquals(scheduler.pending(), action == ExitAction.RESTART ? 1 : 0, "only a restart timer is left");
    }

    @Test
    public void aStopWhileStartingStopsWithoutRestart() {
        readinessWhen(new AtomicBoolean());
        Supervisor supervisor = starting(lifecycle(ExitAction.RESTART, Map.of()));

        supervisor.requestStop();
        supervisor.runPending();
        assertEquals(supervisor.state(), STOPPING);
        launched.get(0).exit(0);
        supervisor.runPending();

        assertEquals(supervisor.state(), STOPPED);
        advance(supervisor, STARTUP_TIMEOUT.plus(RESTART_DELAY));
        assertEquals(launched.size(), 1);
    }

    @Test
    public void aStopWhileStartingUnderLooseLeavesTheApplicationRunning() {
        readinessWhen(new AtomicBoolean());
        Supervisor supervisor = starting(lifecycle(ControlMode.LOOSE, ExitAction.SHUTDOWN, Map.of(), MAX_FAILED,
                Duration.ZERO));

        supervisor.requestStop();
        supervisor.runPending();

        assertEquals(supervisor.state(), STOPPED);
        assertTrue(launched.get(0).isAlive());
    }

    @Test
    public void aStopRequestWhileAnUnreadyInvocationEndsCancelsItsRestart() {
        readinessWhen(new AtomicBoolean());
        Supervisor supervisor = starting(lifecycle(ExitAction.SHUTDOWN, Map.of()));
        launched.get(0).survivingTermination();
        advance(supervisor, STARTUP_TIMEOUT);
        assertEquals(supervisor.state(), RESTART_WAIT);

        supervisor.requestStop();
        supervisor.runPending();
        launched.get(0).exit(0);
        supervisor.runPending();
        advance(supervisor, RESTART_DELAY);

        assertEquals(supervisor.state(), STOPPED);
        assertEquals(launched.size(), 1);
        assertTrue(logged().contains("stopping: jvm 1 has been terminated and will not be restarted"), logged());
    }

    @Test
    public void anApplicationNotReadyThatSurvivesTerminationFailsInsteadOfRestarting() {
        readinessWhen(new AtomicBoolean());
        FakeApplication stubborn = new FakeApplication(1).survivingTermination();
        Supervisor supervisor = supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), invocation -> stubborn);
        supervisor.start();
        supervisor.runPending();

        advance(supervisor, STARTUP_TIMEOUT);
        advance(supervisor, Supervisor.AFTER_TERMINATION.minusMillis(1));
        assertEquals(supervisor.state(), RESTART_WAIT);
        advance(supervisor, Duration.ofMillis(1));

        assertEquals(supervisor.state(), FAILED);
        assertTrue(logged().contains("giving up on it instead of starting the application again"), logged());
    }

    @Test
    public void aReadinessResultThatArrivesAfterTheStateChangedIsIgnored() {
        List<Runnable> checks = new ArrayList<>();
        Supervisor supervisor = new Supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), this::launch, readiness,
                Liveness.NONE, checks::add, stops, scheduler, log, (from, to) -> transitions.add(from + "->" + to));
        supervisor.start();
        supervisor.runPending();
        assertEquals(checks.size(), 1, "the first check is queued at once");

        supervisor.requestStop();
        supervisor.runPending();
        checks.get(0).run(); // the process probe passes, but too late
        supervisor.runPending();

        assertEquals(supervisor.state(), STOPPING);
        assertFalse(transitions.contains("STARTING->RUNNING"), transitions.toString());
    }

    @Test
    public void aReadinessCheckThatThrowsIsANotReadyResult() {
        readiness = new ProbeRunner(List.of(new ProbeRegistry.NamedProbe("broken", context -> {
            throw new IllegalStateException("secret-looking detail");
        })), READINESS_INTERVAL);
        Supervisor supervisor = starting(lifecycle(ExitAction.SHUTDOWN, Map.of()));

        advance(supervisor, STARTUP_TIMEOUT);

        assertEquals(supervisor.state(), RESTART_WAIT);
        assertTrue(logged().contains("last check: broken: the probe threw java.lang.IllegalStateException"),
                logged());
        assertFalse(logged().contains("secret-looking detail"), logged());
    }

    @Test
    public void aProbeExecutorThatRejectsTheCheckFailsSupervision() {
        Supervisor supervisor = new Supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), this::launch, readiness,
                Liveness.NONE, task -> {
                    throw new RejectedExecutionException("shut down");
                }, stops, scheduler, log, (from, to) -> { });
        supervisor.start();
        supervisor.runPending();

        assertEquals(supervisor.state(), FAILED);
        assertEquals(launched.get(0).terminations(), 1);
    }

    /** What a recording stop strategy does when asked. */
    private enum Behaviour { ASKS, ENDS_APPLICATION, THROWS, NOT_APPLICABLE }

    /** A stop strategy that records when it was asked, and with what share. */
    private StopChain.Step recording(String name, List<String> asked, Behaviour behaviour) {
        return new StopChain.Step(name, context -> {
            asked.add(name + "@" + scheduler.now().toSeconds());
            return switch (behaviour) {
                case ASKS -> StopStrategy.Result.REQUESTED;
                case ENDS_APPLICATION -> {
                    ((FakeApplication) context.application()).exit(0);
                    yield StopStrategy.Result.REQUESTED;
                }
                case THROWS -> throw new IllegalStateException(name + " broke");
                case NOT_APPLICABLE -> StopStrategy.Result.NOT_APPLICABLE;
            };
        });
    }

    private void chainOf(StopChain.Step... graceful) {
        stops = new StopChain(List.of(graceful), stops.last(), List.of());
    }

    @Test
    public void gracefulStrategiesShareTheStopBudgetBeforeTerminate() {
        List<String> asked = new ArrayList<>();
        chainOf(recording("first", asked, Behaviour.ASKS), recording("second", asked, Behaviour.ASKS));
        Duration jvmExit = Duration.ofSeconds(10);
        Supervisor supervisor = running(lifecycle(ControlMode.TIGHT, ExitAction.SHUTDOWN, Map.of(), MAX_FAILED,
                Duration.ZERO, STARTUP_TIMEOUT, jvmExit));
        scheduler.advance(Duration.ofSeconds(100)); // so that the times below are relative to the stop

        supervisor.requestStop();
        supervisor.runPending();
        advance(supervisor, Duration.ofSeconds(20).minusMillis(1));
        assertEquals(asked, List.of("first@100"));
        advance(supervisor, Duration.ofMillis(1));
        assertEquals(asked, List.of("first@100", "second@120"), "each gets half of 30 s + 10 s");
        advance(supervisor, Duration.ofSeconds(20).minusMillis(1));
        assertEquals(launched.get(0).terminations(), 0);
        advance(supervisor, Duration.ofMillis(1));

        assertEquals(launched.get(0).terminations(), 1);
        assertEquals(supervisor.state(), STOPPED);
        assertTrue(logged().contains("stopping jvm 1: first, waiting up to 20 s"), logged());
        assertTrue(logged().contains("jvm 1 did not end within 40 s; terminating it (terminate)"), logged());
    }

    @Test
    public void aStrategyThatEndsTheApplicationEndsTheStop() {
        List<String> asked = new ArrayList<>();
        chainOf(recording("first", asked, Behaviour.ENDS_APPLICATION), recording("second", asked, Behaviour.ASKS));
        Supervisor supervisor = running(lifecycle(ExitAction.SHUTDOWN, Map.of()));

        supervisor.requestStop();
        supervisor.runPending();
        assertEquals(scheduler.pending(), 0, "the exit cancelled the step's timer");
        advance(supervisor, SHUTDOWN_TIMEOUT.plus(Supervisor.AFTER_TERMINATION));

        assertEquals(supervisor.outcome(), new Supervisor.Outcome(STOPPED, OptionalInt.of(0)));
        assertEquals(asked, List.of("first@0"));
        assertEquals(launched.get(0).terminations(), 0);
    }

    @Test
    public void aStrategyThatThrowsIsLoggedAndHandsItsShareToTheNextStep() {
        List<String> asked = new ArrayList<>();
        chainOf(recording("broken", asked, Behaviour.THROWS), recording("next", asked, Behaviour.ASKS));
        Supervisor supervisor = running(lifecycle(ExitAction.SHUTDOWN, Map.of()));

        supervisor.requestStop();
        supervisor.runPending();

        assertEquals(asked, List.of("broken@0", "next@0"));
        assertEquals(supervisor.state(), STOPPING);
        assertTrue(logged().contains("the stop strategy broken failed; going on with the next step"), logged());
        assertTrue(logged().contains("stopping jvm 1: next, waiting up to 30 s"), logged());
        advance(supervisor, SHUTDOWN_TIMEOUT.minusMillis(1));
        assertEquals(launched.get(0).terminations(), 0, "the application still has the whole budget");
        advance(supervisor, Duration.ofMillis(1));
        assertEquals(launched.get(0).terminations(), 1);
    }

    @Test
    public void aStrategyThatDoesNotApplyHandsItsShareToTheNextStep() {
        List<String> asked = new ArrayList<>();
        chainOf(recording("first", asked, Behaviour.ASKS), recording("absent", asked, Behaviour.NOT_APPLICABLE),
                recording("third", asked, Behaviour.ASKS));
        Supervisor supervisor = running(lifecycle(ExitAction.SHUTDOWN, Map.of()));

        supervisor.requestStop();
        supervisor.runPending();
        advance(supervisor, Duration.ofSeconds(10)); // first's third of 30 s

        assertEquals(asked, List.of("first@0", "absent@10", "third@10"));
        assertTrue(logged().contains("stopping jvm 1: third, waiting up to 20 s"), logged());
        assertFalse(logged().contains("failed; going on"), logged());
        advance(supervisor, Duration.ofSeconds(20));
        assertEquals(launched.get(0).terminations(), 1);
    }

    @Test
    public void whenNoGracefulStepCouldAskTheApplicationStillHasTheBudget() {
        // The default agent,terminate with no agent connected: the application gets the Ctrl+C in
        // console mode and must have the time to finish its own shutdown.
        List<String> asked = new ArrayList<>();
        chainOf(recording("broken", asked, Behaviour.THROWS), recording("absent", asked, Behaviour.NOT_APPLICABLE));
        Supervisor supervisor = running(lifecycle(ExitAction.SHUTDOWN, Map.of()));

        supervisor.requestStop();
        supervisor.runPending();
        assertEquals(launched.get(0).terminations(), 0, "not at once");
        assertTrue(logged().contains("stopping: no graceful step applied; waiting up to 30 s for jvm 1 to end"),
                logged());

        advance(supervisor, SHUTDOWN_TIMEOUT.minusMillis(1));
        assertEquals(launched.get(0).terminations(), 0);
        advance(supervisor, Duration.ofMillis(1));
        assertEquals(launched.get(0).terminations(), 1);
        assertEquals(supervisor.state(), STOPPED);
    }

    @Test
    public void readinessAndLivenessChecksSayWhatTheyAreFor() {
        List<ProbeContext.Purpose> purposes = new CopyOnWriteArrayList<>();
        Probe recording = context -> {
            purposes.add(context.purpose());
            return ProbeResult.pass("ok");
        };
        readiness = new ProbeRunner(List.of(new ProbeRegistry.NamedProbe("r", recording)), READINESS_INTERVAL);
        Liveness liveness = new Liveness(new ProbeRunner(List.of(new ProbeRegistry.NamedProbe("l", recording)),
                Duration.ofSeconds(10)), 3, FailureAction.RESTART);
        Supervisor supervisor = new Supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), this::launch, readiness,
                liveness, Runnable::run, stops, scheduler, log, (from, to) -> { });
        supervisor.start();
        supervisor.runPending();

        advance(supervisor, Duration.ofSeconds(10));

        assertEquals(purposes, List.of(ProbeContext.Purpose.READINESS, ProbeContext.Purpose.LIVENESS));
    }

    @Test
    public void aStopBudgetOfZeroTerminatesWithoutTheGracefulSteps() {
        List<String> asked = new ArrayList<>();
        chainOf(recording("first", asked, Behaviour.ASKS));
        Supervisor supervisor = running(Lifecycles.withoutStopBudget());

        supervisor.requestStop();
        supervisor.runPending();

        assertEquals(asked, List.of());
        assertEquals(launched.get(0).terminations(), 1);
        assertEquals(supervisor.state(), STOPPED);
    }

    @Test
    public void anApplicationThatSurvivesTheWholeChainIsGivenUp() {
        List<String> asked = new ArrayList<>();
        chainOf(recording("first", asked, Behaviour.ASKS));
        FakeApplication stubborn = new FakeApplication(1).survivingTermination();
        Supervisor supervisor = supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), invocation -> stubborn);
        supervisor.start();
        supervisor.runPending();

        supervisor.requestStop();
        supervisor.runPending();
        advance(supervisor, SHUTDOWN_TIMEOUT);
        advance(supervisor, Supervisor.AFTER_TERMINATION);

        assertEquals(asked, List.of("first@0"));
        assertEquals(stubborn.terminations(), 1);
        assertEquals(supervisor.outcome(), new Supervisor.Outcome(FAILED, OptionalInt.empty()));
    }

    @Test
    public void sharesThatAreNotWholeSecondsAreLoggedWithMilliseconds() {
        List<String> asked = new ArrayList<>();
        chainOf(recording("a", asked, Behaviour.ASKS), recording("b", asked, Behaviour.ASKS),
                recording("c", asked, Behaviour.ASKS), recording("d", asked, Behaviour.ASKS));
        Supervisor supervisor = running(lifecycle(ControlMode.TIGHT, ExitAction.SHUTDOWN, Map.of(), MAX_FAILED,
                Duration.ZERO, STARTUP_TIMEOUT, Duration.ZERO)); // 30 s over 4 steps

        supervisor.requestStop();
        supervisor.runPending();

        assertTrue(logged().contains("stopping jvm 1: a, waiting up to 7.500 s"), logged());
    }

    @Test
    public void theJvmExitTimeoutExtendsTheWaitBeforeTerminate() {
        Supervisor supervisor = running(lifecycle(ControlMode.TIGHT, ExitAction.SHUTDOWN, Map.of(), MAX_FAILED,
                Duration.ZERO, STARTUP_TIMEOUT, Duration.ofSeconds(15)));

        supervisor.requestStop();
        supervisor.runPending();
        advance(supervisor, SHUTDOWN_TIMEOUT);
        assertEquals(launched.get(0).terminations(), 0);
        advance(supervisor, Duration.ofSeconds(15));

        assertEquals(launched.get(0).terminations(), 1);
        assertTrue(logged().contains("stopping: waiting up to 45 s for jvm 1 to end"), logged());
    }

    @Test
    public void anUnreadyInvocationIsEndedByTheLastStepWithoutTheGracefulOnes() {
        List<String> asked = new ArrayList<>();
        chainOf(recording("first", asked, Behaviour.ASKS));
        readinessWhen(new AtomicBoolean());
        Supervisor supervisor = starting(lifecycle(ExitAction.SHUTDOWN, Map.of()));

        advance(supervisor, STARTUP_TIMEOUT);

        assertEquals(asked, List.of());
        assertEquals(launched.get(0).terminations(), 1);
    }

    @Test
    public void anInterruptOfTheRunningThreadIsAStopRequest() throws Exception {
        CountDownLatch supervisorRunning = new CountDownLatch(1);
        CountDownLatch stopping = new CountDownLatch(1);
        Supervisor supervisor = new Supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), this::launch, readiness,
                Liveness.NONE, Runnable::run, stops, scheduler, log, (from, to) -> {
            if (to == RUNNING) {
                supervisorRunning.countDown();
            }
            if (to == STOPPING) {
                stopping.countDown();
            }
        });
        CompletableFuture<Boolean> interruptedAfterwards = new CompletableFuture<>();
        CompletableFuture<Supervisor.Outcome> outcome = new CompletableFuture<>();
        Thread runner = Thread.ofPlatform().start(() -> {
            outcome.complete(supervisor.run());
            interruptedAfterwards.complete(Thread.currentThread().isInterrupted());
        });
        try {
            assertTrue(supervisorRunning.await(1, TimeUnit.MINUTES));
            runner.interrupt();
            assertTrue(stopping.await(1, TimeUnit.MINUTES), "the interrupt asked for a stop");
            launched.get(0).exit(0);

            assertEquals(outcome.get(1, TimeUnit.MINUTES), new Supervisor.Outcome(STOPPED, OptionalInt.of(0)));
            assertTrue(interruptedAfterwards.get(1, TimeUnit.MINUTES), "the interrupt status is set again");
            assertTrue(logged().contains("interrupted: stopping"), logged());
        } finally {
            runner.interrupt();
            runner.join(TimeUnit.MINUTES.toMillis(1));
        }
    }

    @Test
    public void theSupervisorRunsOnlyOnce() {
        Supervisor supervisor = supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()));
        supervisor.start();

        expectThrows(IllegalStateException.class, supervisor::start);
    }

    @Test
    public void runProcessesEventsUntilSupervisionIsOver() throws Exception {
        CountDownLatch supervisorRunning = new CountDownLatch(1);
        CountDownLatch stopping = new CountDownLatch(1);
        Supervisor supervisor = new Supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), this::launch, readiness,
                Liveness.NONE, Runnable::run, stops, scheduler, log, (from, to) -> {
            if (to == RUNNING) {
                supervisorRunning.countDown();
            }
            if (to == STOPPING) {
                stopping.countDown();
            }
        });
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            Future<Supervisor.Outcome> outcome = thread.submit(supervisor::run);
            assertTrue(supervisorRunning.await(1, TimeUnit.MINUTES));
            supervisor.requestStop();
            assertTrue(stopping.await(1, TimeUnit.MINUTES));
            launched.get(0).exit(0);

            assertEquals(outcome.get(1, TimeUnit.MINUTES), new Supervisor.Outcome(STOPPED, OptionalInt.of(0)));
        } finally {
            thread.shutdownNow();
        }
    }

    @Test
    public void everyStateChangeIsAnAllowedTransition() {
        // The supervisor checks each move against State; replaying a scenario's moves through the table
        // makes sure the listener sees exactly those moves.
        Supervisor supervisor = running(lifecycle(ExitAction.RESTART, Map.of()));
        launched.get(0).exit(1);
        supervisor.runPending();
        advance(supervisor, RESTART_DELAY);
        supervisor.requestStop();
        supervisor.runPending();
        advance(supervisor, SHUTDOWN_TIMEOUT);

        List<State> visited = new ArrayList<>(List.of(IDLE));
        for (String transition : transitions) {
            String[] states = transition.split("->");
            assertEquals(State.valueOf(states[0]), visited.get(visited.size() - 1), transition);
            visited.add(State.valueOf(states[1]));
        }
        for (int i = 1; i < visited.size(); i++) {
            assertTrue(visited.get(i - 1).canMoveTo(visited.get(i)), visited.get(i - 1) + " -> " + visited.get(i));
        }
        assertEquals(visited, List.of(IDLE, STARTING, RUNNING, RESTART_WAIT, STARTING, RUNNING, STOPPING, STOPPED));
    }
}
