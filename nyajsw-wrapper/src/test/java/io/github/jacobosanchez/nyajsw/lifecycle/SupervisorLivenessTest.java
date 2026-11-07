package io.github.jacobosanchez.nyajsw.lifecycle;

import static io.github.jacobosanchez.nyajsw.lifecycle.State.FAILED;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.RESTART_WAIT;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.RUNNING;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.STARTING;
import static io.github.jacobosanchez.nyajsw.lifecycle.State.STOPPED;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.config.ControlMode;
import io.github.jacobosanchez.nyajsw.config.ExitAction;
import io.github.jacobosanchez.nyajsw.config.FailureAction;
import io.github.jacobosanchez.nyajsw.config.LifecycleConfig;
import io.github.jacobosanchez.nyajsw.config.LogLevel;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;
import io.github.jacobosanchez.nyajsw.probe.ProbeRegistry;
import io.github.jacobosanchez.nyajsw.probe.ProbeResult;
import io.github.jacobosanchez.nyajsw.probe.ProbeRunner;
import io.github.jacobosanchez.nyajsw.probe.ProcessProbe;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The liveness checks of the {@link Supervisor} (plan P7.1), on a fake clock and fake applications. */
public class SupervisorLivenessTest {

    private static final Duration INTERVAL = Duration.ofSeconds(10);
    private static final int FAILURES = 3;

    private FakeScheduler scheduler;
    private List<FakeApplication> launched;
    private List<String> transitions;
    private ByteArrayOutputStream logBytes;
    private WrapperLog log;
    private StopChain stops;
    /** Whether the liveness probe passes, and how often it was checked. */
    private AtomicBoolean alive;
    private AtomicInteger checks;
    /** Runs the checks: at once by default; a test may hold them back. */
    private Executor probeExecutor;

    @BeforeMethod
    public void setUp() {
        scheduler = new FakeScheduler();
        launched = new CopyOnWriteArrayList<>();
        transitions = new CopyOnWriteArrayList<>();
        logBytes = new ByteArrayOutputStream();
        log = WrapperLog.console(new PrintStream(logBytes, true, StandardCharsets.UTF_8), LogLevel.DEBUG);
        stops = new StopChain(List.of(), new StopChain.Step(TerminateStopStrategy.NAME, new TerminateStopStrategy()),
                List.of());
        alive = new AtomicBoolean(true);
        checks = new AtomicInteger();
        probeExecutor = Runnable::run;
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        log.close();
    }

    private Liveness liveness(FailureAction action) {
        return liveness(action, INTERVAL, FAILURES);
    }

    private Liveness liveness(FailureAction action, Duration interval, int failures) {
        ProbeRegistry.NamedProbe probe = new ProbeRegistry.NamedProbe("custom", context -> {
            checks.incrementAndGet();
            return alive.get() ? ProbeResult.pass("answers") : ProbeResult.fail("no answer");
        });
        return new Liveness(new ProbeRunner(List.of(probe), interval), failures, action);
    }

    private static LifecycleConfig lifecycle(int maxFailedInvocations) {
        return Lifecycles.lifecycle(ControlMode.TIGHT, ExitAction.SHUTDOWN, Map.of(), maxFailedInvocations,
                Duration.ZERO, Lifecycles.STARTUP_TIMEOUT, Duration.ZERO, List.of(TerminateStopStrategy.NAME));
    }

    /** A started supervisor whose readiness is the process probe. */
    private Supervisor started(Liveness liveness, int maxFailedInvocations) {
        ProbeRunner readiness = new ProbeRunner(List.of(new ProbeRegistry.NamedProbe(ProcessProbe.TYPE,
                new ProcessProbe())), Duration.ofSeconds(1));
        Supervisor supervisor = new Supervisor(lifecycle(maxFailedInvocations), invocation -> {
            FakeApplication application = new FakeApplication(invocation);
            launched.add(application);
            return application;
        }, readiness, liveness, probeExecutor, stops, scheduler, log, (from, to) -> transitions.add(from + "->" + to));
        supervisor.start();
        supervisor.runPending();
        transitions.clear();
        return supervisor;
    }

    /** A supervisor whose first invocation is running: the process probe made it ready at once. */
    private Supervisor running(Liveness liveness, int maxFailedInvocations) {
        Supervisor supervisor = started(liveness, maxFailedInvocations);
        assertEquals(supervisor.state(), RUNNING);
        return supervisor;
    }

    private Supervisor running(FailureAction action) {
        return running(liveness(action), Lifecycles.MAX_FAILED);
    }

    private void advance(Supervisor supervisor, Duration by) {
        scheduler.advance(by);
        supervisor.runPending();
    }

    /** Lets {@code count} liveness intervals pass, one at a time: each check arms the next once its result is in. */
    private void intervals(Supervisor supervisor, int count) {
        for (int i = 0; i < count; i++) {
            advance(supervisor, INTERVAL);
        }
    }

    private String logged() {
        return logBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void withoutLivenessProbesNothingIsCheckedWhileTheApplicationRuns() {
        Supervisor supervisor = running(Liveness.NONE, Lifecycles.MAX_FAILED);

        assertEquals(scheduler.pending(), 0, "no liveness timer");
        advance(supervisor, Duration.ofHours(1));
        assertEquals(supervisor.state(), RUNNING);
    }

    @Test
    public void theFirstCheckIsOneIntervalAfterReadinessAndThenEveryInterval() {
        Supervisor supervisor = running(FailureAction.RESTART);

        assertEquals(checks.get(), 0, "not checked at once");
        advance(supervisor, INTERVAL.minusMillis(1));
        assertEquals(checks.get(), 0);
        advance(supervisor, Duration.ofMillis(1));
        assertEquals(checks.get(), 1);
        intervals(supervisor, 4);
        assertEquals(checks.get(), 5);
        assertEquals(supervisor.state(), RUNNING);
    }

    @Test
    public void nothingIsCheckedForLivenessWhileTheApplicationIsStarting() {
        ProbeRunner neverReady = new ProbeRunner(List.of(new ProbeRegistry.NamedProbe("custom",
                context -> ProbeResult.fail("not yet"))), Duration.ofSeconds(1));
        Supervisor supervisor = new Supervisor(lifecycle(Lifecycles.MAX_FAILED), FakeApplication::new, neverReady,
                liveness(FailureAction.RESTART), probeExecutor, stops, scheduler, log, (from, to) -> { });
        supervisor.start();
        supervisor.runPending();

        advance(supervisor, Lifecycles.STARTUP_TIMEOUT.minusSeconds(1));
        assertEquals(supervisor.state(), STARTING);
        assertEquals(checks.get(), 0);
    }

    @Test
    public void fewerFailuresInARowThanTheLimitDoNothingAndAPassResetsTheCount() {
        Supervisor supervisor = running(FailureAction.RESTART);
        FakeApplication application = launched.get(0);

        alive.set(false);
        intervals(supervisor, FAILURES - 1);
        alive.set(true);
        advance(supervisor, INTERVAL);
        alive.set(false);
        intervals(supervisor, FAILURES - 1);

        assertEquals(supervisor.state(), RUNNING);
        assertEquals(application.terminations(), 0);
        assertTrue(logged().contains("jvm 1 failed its liveness check (2 of 3 in a row): custom: no answer"), logged());
        assertTrue(logged().contains("jvm 1 passes its liveness check again (custom)"), logged());
    }

    @Test
    public void restartTerminatesTheApplicationAtOnceAndStartsItAgainOnceItHasEnded() {
        Supervisor supervisor = running(FailureAction.RESTART);
        FakeApplication hung = launched.get(0).survivingTermination();

        alive.set(false);
        intervals(supervisor, FAILURES);

        assertEquals(supervisor.state(), RESTART_WAIT);
        assertEquals(hung.terminations(), 1, "terminated at once, not after wrapper.shutdown.timeout");
        assertTrue(logged().contains("jvm 1 failed 3 liveness checks in a row (wrapper.liveness.failures);"
                + " terminating it; wrapper.liveness.action=RESTART"), logged());
        advance(supervisor, Lifecycles.RESTART_DELAY);
        assertEquals(launched.size(), 1, "no restart before the terminated invocation has ended");

        hung.exit(1);
        supervisor.runPending();
        alive.set(true);
        advance(supervisor, Lifecycles.RESTART_DELAY);
        assertEquals(launched.size(), 2);
        assertEquals(supervisor.state(), RUNNING);
        assertEquals(transitions, List.of("RUNNING->RESTART_WAIT", "RESTART_WAIT->STARTING", "STARTING->RUNNING"));
        checks.set(0);
        advance(supervisor, INTERVAL);
        assertEquals(checks.get(), 1, "the new invocation is checked from the start");
    }

    @Test
    public void aRestartAfterAShortRunCountsAsAFailedInvocation() {
        Supervisor supervisor = running(liveness(FailureAction.RESTART), 1);

        alive.set(false);
        intervals(supervisor, FAILURES);

        assertEquals(supervisor.state(), FAILED, "30 s is less than wrapper.successful_invocation_time");
        assertTrue(logged().contains("giving up after jvm 1"), logged());
        assertEquals(launched.get(0).terminations(), 1);
    }

    @Test
    public void aRestartAfterALongRunIsNotAFailedInvocation() {
        Supervisor supervisor = running(liveness(FailureAction.RESTART), 1);

        advance(supervisor, Lifecycles.SUCCESSFUL_TIME);
        alive.set(false);
        intervals(supervisor, FAILURES);

        assertEquals(supervisor.state(), RESTART_WAIT);
        supervisor.runPending();
        alive.set(true);
        advance(supervisor, Lifecycles.RESTART_DELAY);
        assertEquals(launched.size(), 2);
    }

    @Test
    public void theRunTimeOfARestartEndsAtTheFirstFailedCheck() {
        // 30 s to the first failure, 90 s to the restart: only the 30 s count, which is less than the
        // 60 s of wrapper.successful_invocation_time, so with one failed invocation allowed it gives up.
        Supervisor supervisor = running(liveness(FailureAction.RESTART, Duration.ofSeconds(30), FAILURES), 1);

        alive.set(false);
        for (int i = 0; i < FAILURES; i++) {
            advance(supervisor, Duration.ofSeconds(30));
        }

        assertEquals(supervisor.state(), FAILED);
        assertTrue(logged().contains("giving up after jvm 1"), logged());
    }

    @Test
    public void checksNoLongerCountOnceTheProcessHasEndedSoTheExitActionDecides() {
        Supervisor supervisor = running(liveness(FailureAction.RESTART, INTERVAL, 1), Lifecycles.MAX_FAILED);
        FakeApplication application = launched.get(0);

        application.endProcess(); // its output is still draining: the exit is not reported yet
        alive.set(false);
        intervals(supervisor, 3);
        assertEquals(supervisor.state(), RUNNING);
        assertEquals(application.terminations(), 0);
        assertFalse(logged().contains("failed its liveness check"), logged());

        application.exit(0);
        supervisor.runPending();
        assertEquals(supervisor.state(), STOPPED, "wrapper.on_exit.default=SHUTDOWN applies");
        assertEquals(launched.size(), 1);
    }

    @Test
    public void aStopWhileATerminatedApplicationEndsCancelsTheRestart() {
        Supervisor supervisor = running(FailureAction.RESTART);
        FakeApplication hung = launched.get(0).survivingTermination();
        alive.set(false);
        intervals(supervisor, FAILURES);
        assertEquals(supervisor.state(), RESTART_WAIT);

        supervisor.requestStop();
        supervisor.runPending();
        hung.exit(1);
        advance(supervisor, Lifecycles.RESTART_DELAY);

        assertEquals(supervisor.state(), STOPPED);
        assertEquals(launched.size(), 1);
    }

    @Test
    public void shutdownWaitsForTheTerminatedApplicationAndThenFails() {
        Supervisor supervisor = running(FailureAction.SHUTDOWN);
        FakeApplication hung = launched.get(0).survivingTermination();

        alive.set(false);
        intervals(supervisor, FAILURES);
        assertEquals(supervisor.state(), State.STOPPING, "it has not ended yet");
        assertEquals(hung.terminations(), 1);

        hung.exit(1);
        supervisor.runPending();
        assertEquals(supervisor.state(), FAILED);
        assertEquals(supervisor.outcome().exitCode().getAsInt(), 1);
        assertEquals(transitions, List.of("RUNNING->STOPPING", "STOPPING->FAILED"));
    }

    @Test
    public void shutdownGivesUpOnAnApplicationThatSurvivesItsTermination() {
        Supervisor supervisor = running(FailureAction.SHUTDOWN);
        launched.get(0).survivingTermination();

        alive.set(false);
        intervals(supervisor, FAILURES);
        advance(supervisor, Supervisor.AFTER_TERMINATION);

        assertEquals(supervisor.state(), FAILED);
        assertTrue(logged().contains("is still running 10 s after it was terminated"), logged());
    }

    @Test
    public void shutdownTerminatesTheApplicationAndSupervisionFails() {
        Supervisor supervisor = running(FailureAction.SHUTDOWN);

        alive.set(false);
        intervals(supervisor, FAILURES);

        assertEquals(supervisor.state(), FAILED);
        assertEquals(launched.get(0).terminations(), 1);
        assertEquals(launched.size(), 1);
        assertTrue(logged().contains("wrapper.liveness.action=SHUTDOWN"), logged());
    }

    @Test
    public void anApplicationThatSurvivesItsTerminationIsGivenUpOnInsteadOfStartedTwice() {
        Supervisor supervisor = running(FailureAction.RESTART);
        launched.get(0).survivingTermination();

        alive.set(false);
        intervals(supervisor, FAILURES);
        advance(supervisor, Supervisor.AFTER_TERMINATION);

        assertEquals(supervisor.state(), FAILED);
        assertEquals(launched.size(), 1);
    }

    @Test
    public void aStopEndsTheChecks() {
        Supervisor supervisor = running(FailureAction.RESTART);

        supervisor.requestStop();
        supervisor.runPending();
        advance(supervisor, Lifecycles.SHUTDOWN_TIMEOUT); // no graceful strategy: the budget, then terminate

        assertEquals(supervisor.state(), STOPPED);
        assertEquals(checks.get(), 0);
        assertEquals(scheduler.pending(), 0, "no liveness timer is left");
    }

    @Test
    public void aCheckThatBlocksDelaysTheNextAndOneThatEndsAfterAStopIsIgnored() {
        List<Runnable> held = new ArrayList<>();
        probeExecutor = held::add;
        Supervisor supervisor = started(liveness(FailureAction.RESTART), Lifecycles.MAX_FAILED);
        assertEquals(supervisor.state(), STARTING);
        held.remove(0).run(); // the readiness check is held back too
        supervisor.runPending();
        assertEquals(supervisor.state(), RUNNING);

        intervals(supervisor, 5);
        assertEquals(held.size(), 1, "one check at a time");

        alive.set(false);
        supervisor.requestStop();
        supervisor.runPending();
        held.remove(0).run();
        supervisor.runPending();
        assertEquals(supervisor.state(), State.STOPPING);
        assertFalse(logged().contains("failed its liveness check"), logged());
    }

    @Test
    public void livenessNeedsAtLeastOneFailureToAct() {
        ProbeRunner none = new ProbeRunner(List.of(), INTERVAL);

        expectThrows(IllegalArgumentException.class, () -> new Liveness(none, 0, FailureAction.RESTART));
        assertFalse(Liveness.NONE.isEnabled());
        assertTrue(liveness(FailureAction.RESTART).isEnabled());
    }
}
