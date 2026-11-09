package io.jsl.nyajsw.lifecycle;

import static io.jsl.nyajsw.log.Durations.seconds;

import io.jsl.nyajsw.config.ConfigKeys;
import io.jsl.nyajsw.config.DebugDetail;
import io.jsl.nyajsw.config.ExitAction;
import io.jsl.nyajsw.config.LifecycleConfig;
import io.jsl.nyajsw.launch.ApplicationLauncher;
import io.jsl.nyajsw.launch.LaunchException;
import io.jsl.nyajsw.launch.RunningApplication;
import io.jsl.nyajsw.log.WrapperLog;
import io.jsl.nyajsw.probe.ProbeContext;
import io.jsl.nyajsw.probe.ProbeResult;
import io.jsl.nyajsw.probe.ProbeRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Supervises the application (plan §4.6): starts it, waits until it is ready, decides what an exit
 * means, and stops it on request. The states and the moves between them are those of {@link State}.
 *
 * <p>Everything happens on the thread that calls {@link #run()}, one event at a time: the start, an
 * exit of the application, a stop request from any thread ({@link #requestStop()}), the result of a
 * readiness or liveness check and the timers of the {@link Scheduler}. No decision depends on how threads
 * interleave, and tests replace the process, the probes and the clock with fakes.
 *
 * <p>Timers belong to the state they were started in: every change of state cancels them, and one
 * that fires after that is ignored.
 *
 * <p>An unexpected error while handling an event (a collaborator that throws, a broken invariant)
 * does not leave the application unsupervised: it is logged, a running application is terminated
 * and supervision ends {@link State#FAILED FAILED}.
 *
 * <p><b>Readiness.</b> A launched invocation is {@link State#STARTING STARTING} until the {@link
 * ProbeRunner}'s probes all pass; they are checked at once and then every {@link
 * ProbeRunner#interval()}, on the probe executor, since a check may block. When they have not passed
 * within {@code wrapper.startup.timeout} (0: no limit) the invocation has failed: it is terminated
 * at once, as the startup timeout of YAJSW and Tanuki does, whatever {@code wrapper.control} says,
 * and, if the restart policy allows, started again {@code wrapper.restart.delay} after it has ended.
 * That happens in {@link State#RESTART_WAIT RESTART_WAIT}, never {@code STOPPING}, so a listener
 * reporting to the SCM never takes a restart for a stop.
 *
 * <p><b>Liveness.</b> While the application is {@code RUNNING}, the {@link Liveness} probes are
 * checked every interval, the first time one interval after it became ready; the next check waits
 * for the result of the one before, so a check that blocks delays the next instead of piling up.
 * After {@code wrapper.liveness.failures} failed checks in a row the application is terminated at
 * once, as YAJSW does when its ping times out, since an application that fails its checks may not
 * answer a graceful stop either; then {@code wrapper.liveness.action} decides: {@code RESTART}
 * starts it again like an invocation that ended by itself, its run time counted up to when the last
 * check that passed began, readiness or liveness, since the application was not seen healthy after
 * that (towards {@code wrapper.successful_invocation_time}); {@code SHUTDOWN}
 * waits in {@code STOPPING} for it to end and then ends supervision {@link State#FAILED FAILED}, so
 * that the wrapper does not report the end of an unhealthy application as success. A check that
 * passes resets the count. Once the process has ended, checks no longer count: its exit is on the
 * way (after its output has drained), and the exit action decides, not a probe that saw it gone.
 *
 * <p><b>Exits.</b> What an exit means is decided only while the application runs as it should
 * ({@code STARTING} or {@code RUNNING}): once the supervisor is {@link State#STOPPING STOPPING}, the
 * application ends because the wrapper asked, so {@code wrapper.on_exit.*} does not apply. Otherwise
 * an exit is decided by {@link ExitActionResolver} ({@code wrapper.on_exit.*} with {@code
 * wrapper.control}), and a restart is subject to the {@link RestartPolicy}: an invocation that cannot
 * be started, does not become ready, or ends before {@code wrapper.successful_invocation_time}, is a
 * failure, and after {@code wrapper.max_failed_invocations} of them in a row supervision ends {@link
 * State#FAILED FAILED}. A launch error is retried like such a failure, whatever the exit actions say,
 * since there is no exit code to look up. The first start waits {@code wrapper.startup.delay}.
 *
 * <p><b>Stops.</b> A stop request stops the application only when {@code wrapper.control} ties it to
 * the wrapper ({@code TIGHT}, {@code APPLICATION}); under {@code LOOSE} and {@code WRAPPER}
 * supervision ends and the application keeps running, as YAJSW documents. The safety nets do not
 * follow that rule: after an unexpected error, or when the exit of the application can no longer be
 * observed, a live application is terminated whatever {@code wrapper.control} says, since nothing
 * would watch it. A stop request while a terminated invocation (not ready, or not alive) is ending cancels its
 * restart.
 *
 * <p>A stop runs the {@link StopChain}: the graceful strategies of {@code wrapper.stop.strategy}
 * share the stop budget, then {@code terminate} ends the application by force; one still alive 10 s
 * after that is given up on, and supervision ends {@link State#FAILED FAILED}.
 */
public final class Supervisor {

    /** How long a terminated application may take to be gone before the supervisor gives up on it. */
    public static final Duration AFTER_TERMINATION = Duration.ofSeconds(10);

    /**
     * The longest wait the wrapper measures: as many nanoseconds as a {@code long} holds (292
     * years), so that any wait fits a monotonic ticker. Longer configured times are cut to it.
     */
    public static final Duration LONGEST_WAIT = Duration.ofNanos(Long.MAX_VALUE);

    /**
     * What a wait for a stop from outside the supervisor allows beyond the stop itself: the
     * supervisor's own work and the report that it has ended.
     */
    public static final Duration STOP_WAIT_MARGIN = Duration.ofSeconds(10);

    /**
     * How long to wait for a stop with {@code stopBudget} to end: the budget the stop strategies
     * share, the wait for the terminated application ({@link #AFTER_TERMINATION}) and {@link
     * #STOP_WAIT_MARGIN}. Console mode, {@code -p} and the service's pre-shutdown timeout all wait
     * this long, so they cannot drift apart.
     */
    public static Duration stopWait(Duration stopBudget) {
        return sum(stopBudget, AFTER_TERMINATION, STOP_WAIT_MARGIN);
    }

    /** {@code parts} added up, at most {@link #LONGEST_WAIT}. */
    public static Duration sum(Duration... parts) {
        Duration sum = Duration.ZERO;
        try {
            for (Duration part : parts) {
                sum = sum.plus(part);
            }
        } catch (ArithmeticException e) { // beyond Duration itself
            return LONGEST_WAIT;
        }
        return sum.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : sum;
    }

    /**
     * How supervision ended.
     *
     * @param state    {@link State#STOPPED} or {@link State#FAILED}
     * @param exitCode the exit code of the last invocation that exited, if any did
     */
    public record Outcome(State state, OptionalInt exitCode) {

        public Outcome {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(exitCode, "exitCode");
        }
    }

    /**
     * Told about every change of state, on the supervisor's thread, after the change. The service
     * host reports them to the SCM (P6); a listener that throws is logged and otherwise ignored.
     */
    @FunctionalInterface
    public interface StateListener {

        /** The supervisor has moved from {@code from} to {@code to}. */
        void changed(State from, State to);
    }

    private sealed interface Event
            permits Start, ProcessEnded, Exited, StopRequested, ReadinessChecked, LivenessChecked, TimerFired {
    }

    /** The process of invocation {@code invocation} ended at {@code at}, on the scheduler's clock; its exit follows. */
    private record ProcessEnded(int invocation, Duration at) implements Event {
    }

    private record Start() implements Event {
    }

    /** Invocation {@code invocation} ended; {@code error} is set when its exit could not be observed. */
    private record Exited(int invocation, Integer code, Throwable error) implements Event {
    }

    private record StopRequested() implements Event {
    }

    /** {@code startedAt} is when the check was handed to the probe executor, on the scheduler's clock. */
    private record ReadinessChecked(int invocation, Duration startedAt, ProbeResult result) implements Event {
    }

    /** {@code startedAt} is when the check was handed to the probe executor, on the scheduler's clock. */
    private record LivenessChecked(int invocation, Duration startedAt, ProbeResult result) implements Event {
    }

    /** Makes the event that brings a check's result back to the supervisor's thread. */
    @FunctionalInterface
    private interface CheckedEvent {
        Event of(int invocation, Duration startedAt, ProbeResult result);
    }

    private record TimerFired(long id) implements Event {
    }

    private final LifecycleConfig lifecycle;
    private final ExitActionResolver exits;
    private final RestartPolicy restarts;
    private final ApplicationLauncher launcher;
    private final ProbeRunner readiness;
    private final Liveness liveness;
    private final Executor probeExecutor;
    private final StopChain stops;
    private final Scheduler scheduler;
    private final WrapperLog log;
    private final StateListener listener;
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final AtomicBoolean started = new AtomicBoolean();

    // Written only by the thread that processes events; state is also read by others.
    private volatile State state = State.IDLE;
    private int invocation;
    private RunningApplication application;
    /** When the current invocation was started, on the scheduler's clock. */
    private Duration launchedAt;
    /** When its process ended, if known yet: the end of its run time, before its output was drained. */
    private Duration processEndedAt;
    private Integer lastExitCode;
    /** What the last readiness check of the current invocation found. */
    private String lastReadiness;
    /** Failed liveness checks in a row of the current invocation. */
    private int livenessFailures;
    /** When the last check that passed began, readiness or liveness: the last time it was seen healthy. */
    private Duration lastHealthyAt;
    /** Whether the current stop ends supervision {@code FAILED}: a liveness {@code SHUTDOWN}. */
    private boolean failWhenStopped;
    /** When the current stop began and when its budget runs out, on the scheduler's clock. */
    private Duration stopStartedAt;
    private Duration stopDeadline;
    /** Whether a graceful step of the current stop asked the application to end. */
    private boolean gracefullyAsked;
    /** The timers of the current state, by id; a fired id that is missing is ignored. */
    private final Map<Long, PendingTimer> timers = new HashMap<>();
    private long timersStarted;

    private record PendingTimer(Runnable action, Scheduler.Timer handle) {
    }

    /**
     * @param lifecycle     the supervision settings
     * @param launcher      starts each invocation
     * @param readiness     the readiness probes and how often to check them
     * @param liveness      the liveness probes, how often to check them and what their failure means
     * @param probeExecutor runs the readiness and liveness checks, which may block, off the
     *                      supervisor's thread; one check runs at a time per invocation, but a check
     *                      that hangs must not hold up the next invocation's, so give each check its
     *                      own thread (virtual threads suit) rather than a single shared one
     * @param stops         the steps of a stop, from {@code wrapper.stop.strategy}
     * @param scheduler     runs the timers and tells the time
     * @param log           the wrapper log
     * @param listener      told about every change of state
     */
    public Supervisor(LifecycleConfig lifecycle, ApplicationLauncher launcher, ProbeRunner readiness, Liveness liveness,
            Executor probeExecutor, StopChain stops, Scheduler scheduler, WrapperLog log, StateListener listener) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.exits = new ExitActionResolver(lifecycle);
        this.restarts = new RestartPolicy(lifecycle.maxFailedInvocations(), lifecycle.successfulInvocationTime());
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.liveness = Objects.requireNonNull(liveness, "liveness");
        this.probeExecutor = Objects.requireNonNull(probeExecutor, "probeExecutor");
        this.stops = Objects.requireNonNull(stops, "stops");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.log = Objects.requireNonNull(log, "log");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Supervises the application until supervision is over. Call it once. A stop requested before
     * the call ends supervision without starting anything. An interrupt of the calling thread is a
     * stop request, so the application is never left unwatched; the thread's interrupt status is set
     * again when this returns.
     *
     * @return how supervision ended
     */
    public Outcome run() {
        start();
        boolean interrupted = false;
        while (!state.isTerminal()) {
            try {
                handle(events.take());
            } catch (InterruptedException e) {
                if (!interrupted) {
                    interrupted = true;
                    log.status("interrupted: stopping");
                    requestStop();
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return outcome();
    }

    /** Asks the supervisor to stop the application and end supervision; callable from any thread, any time. */
    public void requestStop() {
        events.add(new StopRequested());
    }

    /** The current state; from another thread it may be out of date by the time it is used. */
    public State state() {
        return state;
    }

    /** Queues the start; {@link #run()} and tests call it once. */
    void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("the supervisor has already run");
        }
        events.add(new Start());
    }

    /** Processes the queued events without waiting for more, for tests that drive the supervisor step by step. */
    void runPending() {
        Event event;
        while (!state.isTerminal() && (event = events.poll()) != null) {
            handle(event);
        }
    }

    /** How supervision ended, or would end if it ended now. */
    Outcome outcome() {
        return new Outcome(state, lastExitCode == null ? OptionalInt.empty() : OptionalInt.of(lastExitCode));
    }

    private void handle(Event event) {
        try {
            process(event);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    /** Ends supervision after an unexpected error, so that the wrapper does not hang with the application unwatched. */
    private void fail(RuntimeException error) {
        log.error("supervision failed unexpectedly in state " + state, error);
        // Straight to the process tree, not through the stop chain: the chain is what may have failed.
        RunningApplication left = application;
        application = null;
        if (left != null) {
            try {
                if (left.isAlive()) {
                    left.terminateTree();
                }
            } catch (RuntimeException e) {
                log.error("cannot terminate " + left.name(), e);
            }
        }
        if (!state.isTerminal()) {
            moveTo(State.FAILED);
        }
    }

    private void process(Event event) {
        switch (event) {
            case Start start -> {
                if (state == State.IDLE && invocation == 0) {
                    begin();
                }
            }
            case ProcessEnded ended -> {
                if (ended.invocation() == invocation && processEndedAt == null) {
                    processEndedAt = ended.at();
                }
            }
            case Exited exited -> exited(exited);
            case StopRequested request -> stopRequested();
            case ReadinessChecked checked -> readinessChecked(checked);
            case LivenessChecked checked -> livenessChecked(checked);
            case TimerFired fired -> {
                PendingTimer timer = timers.remove(fired.id());
                if (timer != null) {
                    timer.action().run();
                }
            }
        }
    }

    private void begin() {
        Duration delay = lifecycle.startupDelay();
        if (delay.isZero()) {
            launch();
        } else {
            log.status("starting in " + delay.toSeconds() + " s (" + ConfigKeys.STARTUP_DELAY.name() + ")");
            startTimer(delay, this::launch);
        }
    }

    private void launch() {
        invocation++;
        lastExitCode = null; // the outcome reports this invocation's exit, not an earlier one's
        lastReadiness = "not checked yet";
        moveTo(State.STARTING);
        readiness.beforeLaunch();
        liveness.probes().beforeLaunch();
        RunningApplication launched;
        try {
            launched = launcher.launch(invocation);
        } catch (LaunchException e) {
            String name = ApplicationLauncher.nameOf(invocation);
            log.error("cannot start " + name + ": " + e.getMessage());
            restarts.failed();
            restartOrGiveUp(name);
            return;
        }
        launchedAt = scheduler.now();
        processEndedAt = null;
        application = launched;
        int current = invocation;
        // The time is read when the process ends, not when the event is handled, nor when the exit
        // comes, which waits for the output to drain.
        launched.onProcessEnd().whenComplete((ignored, error) -> events.add(new ProcessEnded(current,
                scheduler.now())));
        launched.onExit().whenComplete((code, error) -> events.add(new Exited(current, code, error)));
        if (!lifecycle.startupTimeout().isZero()) { // 0: no limit, as in the Tanuki format YAJSW files inherit
            startTimer(lifecycle.startupTimeout(), this::notReady);
        }
        checkReadiness();
    }

    private void checkReadiness() {
        check(readiness, ProbeContext.Purpose.READINESS, ReadinessChecked::new);
    }

    private void checkLiveness() {
        check(liveness.probes(), ProbeContext.Purpose.LIVENESS, LivenessChecked::new);
    }

    /** Checks {@code probes} on the probe executor; the result comes back as an event, whatever happens. */
    private void check(ProbeRunner probes, ProbeContext.Purpose purpose, CheckedEvent resultEvent) {
        int current = invocation;
        // Taken here, before the probes run: a slow check's own time must not count as healthy run time.
        Duration startedAt = scheduler.now();
        String kind = purpose.name().toLowerCase(Locale.ROOT);
        ProbeContext context = new ProbeContext(application, purpose);
        probeExecutor.execute(() -> {
            // ProbeRunner already turns a probe's exception into a failure; this is the backstop for
            // anything else (an Error included), so that the supervisor always hears back.
            ProbeResult result = ProbeResult.fail("the " + kind + " check did not complete");
            try {
                result = probes.check(context);
            } catch (RuntimeException e) {
                result = ProbeResult.fail("the " + kind + " check threw " + e.getClass().getName());
            } finally {
                events.add(resultEvent.of(current, startedAt, result));
            }
        });
    }

    private void readinessChecked(ReadinessChecked checked) {
        if (state != State.STARTING || checked.invocation() != invocation) {
            return; // a check that finished after the invocation stopped starting
        }
        ProbeResult result = checked.result();
        if (result.passed()) {
            log.status(application.name() + " is ready (" + result.detail() + ")");
            moveTo(State.RUNNING);
            livenessFailures = 0;
            lastHealthyAt = checked.startedAt();
            if (liveness.isEnabled()) {
                startTimer(liveness.probes().interval(), this::checkLiveness);
            }
            return;
        }
        lastReadiness = result.detail();
        log.debug(DebugDetail.SUPERVISION, () -> application.name() + " is not ready yet: " + result.detail());
        startTimer(readiness.interval(), this::checkReadiness);
    }

    /**
     * The invocation did not become ready in time: it has failed. It is terminated at once, as the
     * Tanuki and YAJSW startup timeout does, not stopped gracefully, since it is not a stop of the
     * wrapper; the restart waits in {@code RESTART_WAIT} until it has ended, so a listener never
     * mistakes it for a stop.
     */
    private void notReady() {
        String name = application.name();
        log.error(name + " did not become ready within " + lifecycle.startupTimeout().toSeconds() + " s ("
                + ConfigKeys.STARTUP_TIMEOUT.name() + "); last check: " + lastReadiness + "; terminating it");
        restarts.failed();
        terminateNow();
        restartOnceEnded(name);
    }

    private void livenessChecked(LivenessChecked checked) {
        if (state != State.RUNNING || checked.invocation() != invocation) {
            return; // a check that finished after the application stopped running
        }
        if (processEndedAt != null) {
            return; // the process has ended: its exit follows and the exit action decides
        }
        ProbeResult result = checked.result();
        String name = application.name();
        if (result.passed()) {
            if (livenessFailures > 0) {
                log.status(name + " passes its liveness check again (" + result.detail() + ")");
            }
            livenessFailures = 0;
            lastHealthyAt = checked.startedAt();
            log.debug(DebugDetail.SUPERVISION, () -> name + " is alive: " + result.detail());
        } else {
            livenessFailures++;
            log.status(name + " failed its liveness check (" + livenessFailures + " of " + liveness.failures()
                    + " in a row): " + result.detail());
            if (livenessFailures >= liveness.failures()) {
                notAlive(name);
                return;
            }
        }
        startTimer(liveness.probes().interval(), this::checkLiveness);
    }

    /**
     * The running invocation failed its liveness checks too often: it is terminated at once, like
     * one that did not become ready, and {@code wrapper.liveness.action} decides what follows.
     */
    private void notAlive(String name) {
        log.error(name + " failed " + livenessFailures + " liveness checks in a row ("
                + ConfigKeys.LIVENESS_FAILURES.name() + "); terminating it; " + ConfigKeys.LIVENESS_ACTION.name()
                + "=" + liveness.action());
        terminateNow();
        switch (liveness.action()) {
            case RESTART -> {
                // Run time up to when it was last seen healthy: an application that turns unhealthy
                // at once must count as a failed invocation, however long the checks took to give up.
                restarts.ended(lastHealthyAt.minus(launchedAt));
                restartOnceEnded(name);
            }
            case SHUTDOWN -> {
                failWhenStopped = true;
                moveTo(State.STOPPING);
                startTimer(AFTER_TERMINATION, this::abandon);
            }
            default -> throw new IllegalStateException("no handling for " + liveness.action());
        }
    }

    /** Ends the current invocation by force with the last step of the stop chain, without waiting. */
    private void terminateNow() {
        stops.last().strategy().requestStop(new StopStrategy.Context(application, log, Duration.ZERO));
    }

    /**
     * After a terminated invocation: restarts {@code wrapper.restart.delay} after it has ended, from
     * {@code RESTART_WAIT}, or gives up when the restart policy allows no more.
     */
    private void restartOnceEnded(String name) {
        if (!restarts.mayRestart()) {
            giveUp(name);
            return;
        }
        moveTo(State.RESTART_WAIT);
        log.status("restarting " + lifecycle.restartDelay().toSeconds() + " s after " + name + " has ended"
                + " (failed invocations in a row: " + restarts.failures() + " of " + restarts.maxFailedInvocations()
                + ")");
        startTimer(AFTER_TERMINATION, this::abandonTerminated);
    }

    /** A terminated invocation is still running: restarting beside it would run the application twice. */
    private void abandonTerminated() {
        log.error(application.name() + " is still running " + AFTER_TERMINATION.toSeconds()
                + " s after it was terminated; giving up on it instead of starting the application again");
        application = null;
        moveTo(State.FAILED);
    }

    private void exited(Exited exited) {
        if (exited.invocation() != invocation || application == null) {
            return; // an earlier run, already dealt with
        }
        RunningApplication ended = application;
        String name = ended.name();
        application = null;
        if (exited.error() != null) {
            log.error("lost track of " + name + "; its exit could not be observed", exited.error());
            if (ended.isAlive()) {
                ended.terminateTree(); // unwatched, it must not keep running
            }
            moveTo(state == State.STOPPING && !failWhenStopped ? State.STOPPED : State.FAILED);
            return;
        }
        int code = exited.code();
        lastExitCode = code;
        switch (state) {
            case STOPPING -> {
                log.debug(DebugDetail.APPLICATION, () -> name + " ended while stopping; no exit action applies");
                moveTo(failWhenStopped ? State.FAILED : State.STOPPED);
            }
            case STARTING, RUNNING -> applyExitAction(name, code);
            case RESTART_WAIT -> { // a terminated invocation (not ready, or not alive) has ended
                cancelTimers();
                startTimer(lifecycle.restartDelay(), this::launch);
            }
            default -> throw new IllegalStateException(name + " exited while " + state);
        }
    }

    private void applyExitAction(String name, int code) {
        // Run time to the end of the process: draining its output, or removing a pid file, is not run time.
        Duration ended = processEndedAt != null ? processEndedAt : scheduler.now();
        restarts.ended(ended.minus(launchedAt));
        ExitActionResolver.Decision decision = exits.resolve(code);
        log.status(name + " exit code " + code + " means " + decision.action());
        switch (decision.next()) {
            case RESTART -> restartOrGiveUp(name);
            case END -> {
                if (decision.action() == ExitAction.STOP) {
                    log.status("STOP ends the wrapper as well: " + ConfigKeys.CONTROL.name() + "="
                            + lifecycle.control() + " ends the wrapper with the application");
                }
                moveTo(State.STOPPED);
            }
            case WAIT -> {
                log.status("the wrapper keeps running without the application until it is stopped ("
                        + ConfigKeys.CONTROL.name() + "=" + lifecycle.control() + ")");
                moveTo(State.IDLE);
            }
        }
    }

    /** After a failed or ended invocation that is to be restarted: restarts after the delay, or gives up. */
    private void restartOrGiveUp(String name) {
        if (!restarts.mayRestart()) {
            giveUp(name);
            return;
        }
        moveTo(State.RESTART_WAIT);
        Duration delay = lifecycle.restartDelay();
        log.status("restarting in " + delay.toSeconds() + " s (failed invocations in a row: " + restarts.failures()
                + " of " + restarts.maxFailedInvocations() + ")");
        startTimer(delay, this::launch);
    }

    private void giveUp(String name) {
        log.error("giving up after " + name + ": " + restarts.failures() + " failed invocation(s) in a row reach "
                + ConfigKeys.MAX_FAILED_INVOCATIONS.name() + " (limit " + restarts.maxFailedInvocations()
                + "; an invocation fails when it cannot be started, does not become ready, or ends within "
                + ConfigKeys.SUCCESSFUL_INVOCATION_TIME.name() + ", "
                + lifecycle.successfulInvocationTime().toSeconds() + " s)");
        moveTo(State.FAILED);
    }

    private void stopRequested() {
        switch (state) {
            case IDLE, RESTART_WAIT -> {
                if (application != null) {
                    log.status("stopping: " + application.name() + " has been terminated and will not be restarted");
                    application = null;
                } else {
                    log.status("stopping: no application is running");
                }
                moveTo(State.STOPPED);
            }
            case STARTING, RUNNING -> {
                if (lifecycle.control().applicationEndsWithWrapper()) {
                    beginStop();
                } else {
                    log.status("stopping the wrapper only: " + application.name() + " (pid " + application.pid()
                            + ") keeps running (" + ConfigKeys.CONTROL.name() + "=" + lifecycle.control() + ")");
                    application = null;
                    moveTo(State.STOPPED);
                }
            }
            case STOPPING, STOPPED, FAILED ->
                    log.debug(DebugDetail.SUPERVISION, "stop requested again; already " + state);
        }
    }

    /**
     * Runs the stop chain within the stop budget ({@code wrapper.shutdown.timeout} plus {@code
     * wrapper.jvm_exit.timeout}): each graceful strategy asks the application to end and gets an
     * equal share of the time left until the deadline, so a step that does not apply hands its
     * share on. Without graceful strategies, or when none of them could ask (no agent connected), the
     * whole budget, or what is left of it, is waited, which in console mode is the time the
     * application has after the Ctrl+C it receives too. At the deadline the last step ends
     * it by force; with a budget of zero at once, without the graceful steps, since asking and
     * killing in the same instant would cut its shutdown short.
     */
    private void beginStop() {
        moveTo(State.STOPPING);
        Duration budget = lifecycle.stopBudget();
        stopStartedAt = scheduler.now();
        stopDeadline = stopStartedAt.plus(budget);
        if (budget.isZero()) {
            forceStop();
        } else if (stops.graceful().isEmpty()) {
            log.status("stopping: waiting up to " + seconds(budget) + " for " + application.name() + " to end");
            startTimer(budget, this::forceStop);
        } else {
            gracefullyAsked = false;
            graceful(0);
        }
    }

    private void graceful(int index) {
        List<StopChain.Step> steps = stops.graceful();
        Duration left = stopDeadline.minus(scheduler.now());
        if (index == steps.size() && !gracefullyAsked && left.isPositive()) {
            // No step could ask (no agent connected, say): the application still has the rest of the
            // budget, as without graceful steps, for a shutdown of its own (Ctrl+C in console mode).
            log.status("stopping: no graceful step applied; waiting up to " + seconds(left) + " for "
                    + application.name() + " to end");
            startTimer(left, this::forceStop);
            return;
        }
        if (index == steps.size() || !left.isPositive()) {
            forceStop();
            return;
        }
        Duration share = left.dividedBy(steps.size() - index);
        StopChain.Step step = steps.get(index);
        log.status("stopping " + application.name() + ": " + step.name() + ", waiting up to " + seconds(share));
        StopStrategy.Result result;
        try {
            result = step.strategy().requestStop(new StopStrategy.Context(application, log, share));
        } catch (RuntimeException e) {
            log.error("the stop strategy " + step.name() + " failed; going on with the next step", e);
            result = StopStrategy.Result.NOT_APPLICABLE;
        }
        if (result == StopStrategy.Result.NOT_APPLICABLE) {
            log.debug(DebugDetail.SUPERVISION, () -> step.name() + " did not apply; going on with the next step");
            graceful(index + 1);
        } else {
            gracefullyAsked = true;
            startTimer(share, () -> graceful(index + 1));
        }
    }

    private void forceStop() {
        // The budget once it is spent (a real timer fires a little late); less when every step gave up early.
        Duration spent = scheduler.now().minus(stopStartedAt);
        Duration budget = lifecycle.stopBudget();
        log.status(application.name() + " did not end within " + seconds(spent.compareTo(budget) >= 0 ? budget : spent)
                + "; terminating it (" + stops.last().name() + ")");
        terminateNow();
        startTimer(AFTER_TERMINATION, this::abandon);
    }

    /** Supervision gave up: the application could not be stopped, which a caller must not take for success. */
    private void abandon() {
        log.error(application.name() + " is still running " + AFTER_TERMINATION.toSeconds()
                + " s after it was terminated; giving up on it");
        application = null;
        moveTo(State.FAILED);
    }

    private void startTimer(Duration delay, Runnable action) {
        long id = ++timersStarted;
        // The task only queues an event, handled on this thread after the entry exists.
        timers.put(id, new PendingTimer(action, scheduler.schedule(delay, () -> events.add(new TimerFired(id)))));
    }

    private void cancelTimers() {
        List<PendingTimer> cancelled = new ArrayList<>(timers.values());
        timers.clear();
        for (PendingTimer timer : cancelled) {
            try {
                timer.handle().cancel();
            } catch (RuntimeException e) {
                log.debug(() -> "cannot cancel a timer: " + e); // it is ignored when it fires anyway
            }
        }
    }

    private void moveTo(State target) {
        State from = state;
        if (!from.canMoveTo(target)) {
            throw new IllegalStateException("no transition from " + from + " to " + target);
        }
        cancelTimers();
        state = target;
        log.debug(DebugDetail.APPLICATION, () -> "state " + from + " -> " + target);
        try {
            listener.changed(from, target);
        } catch (RuntimeException e) {
            log.error("a state listener failed on " + from + " -> " + target, e);
        }
    }
}
