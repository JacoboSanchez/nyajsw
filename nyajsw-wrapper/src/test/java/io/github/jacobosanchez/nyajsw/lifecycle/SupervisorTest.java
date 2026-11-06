package io.github.jacobosanchez.nyajsw.lifecycle;

import static io.github.jacobosanchez.nyajsw.lifecycle.State.FAILED;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.IDLE;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.RESTART_WAIT;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.RUNNING;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.STARTING;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.STOPPED;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.STOPPING;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.config.ControlMode;
import io.github.jacobosanchez.nyajsw.config.ExitAction;
import io.github.jacobosanchez.nyajsw.config.LifecycleConfig;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.launch.ApplicationLauncher;
import io.github.jacobosanchez.nyajsw.launch.LaunchException;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class SupervisorTest {

    private static final Duration RESTART_DELAY = Duration.ofSeconds(5);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SUCCESSFUL_TIME = Duration.ofSeconds(60);
    private static final int MAX_FAILED = 5;

    private FakeScheduler scheduler;
    private List<FakeApplication> launched;
    private List<String> transitions;
    private ByteArrayOutputStream logBytes;
    private WrapperLog log;

    @BeforeMethod
    public void setUp() {
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
        return new LifecycleConfig(control, actions, defaultAction, RESTART_DELAY, maxFailedInvocations,
                SUCCESSFUL_TIME, startupDelay, Duration.ofSeconds(30), SHUTDOWN_TIMEOUT, Optional.empty(),
                Optional.empty(), List.of("terminate"), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private Supervisor supervisor(LifecycleConfig lifecycle) {
        return supervisor(lifecycle, this::launch);
    }

    private Supervisor supervisor(LifecycleConfig lifecycle, ApplicationLauncher launcher) {
        return new Supervisor(lifecycle, launcher, scheduler, log, (from, to) -> transitions.add(from + "->" + to));
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
        assertTrue(logged().contains("jvm 1 did not end within 30 s; terminating it"), logged());
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
        Supervisor supervisor = new Supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), invocation -> stubborn,
                scheduler, log, (from, to) -> { });
        supervisor.start();
        supervisor.runPending();

        supervisor.requestStop();
        supervisor.runPending();
        advance(supervisor, SHUTDOWN_TIMEOUT);
        assertEquals(stubborn.terminations(), 1);
        assertEquals(supervisor.state(), STOPPING);

        advance(supervisor, Supervisor.AFTER_TERMINATION);
        assertEquals(supervisor.outcome(), new Supervisor.Outcome(STOPPED, OptionalInt.empty()));
        assertTrue(logged().contains("jvm 1 is still running 10 s after it was terminated; giving up on it"),
                logged());

        stubborn.exit(9); // too late to matter
        supervisor.runPending();
        assertEquals(supervisor.outcome(), new Supervisor.Outcome(STOPPED, OptionalInt.empty()));
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

        assertEquals(supervisor.state(), STOPPED);
        assertEquals(stubborn.terminations(), 1);
    }

    @Test
    public void aFailingListenerIsLoggedAndSupervisionGoesOn() {
        Supervisor supervisor = new Supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), this::launch,
                scheduler, log, (from, to) -> {
            throw new IllegalStateException("listener broke");
        });

        supervisor.start();
        supervisor.runPending();
        launched.get(0).exit(0);
        supervisor.runPending();

        assertEquals(supervisor.state(), STOPPED);
        assertTrue(logged().contains("a state listener failed on IDLE -> STARTING"), logged());
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
        Supervisor supervisor = new Supervisor(lifecycle(ExitAction.SHUTDOWN, Map.of()), this::launch,
                scheduler, log, (from, to) -> {
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
