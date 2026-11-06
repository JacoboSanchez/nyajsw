package io.github.jacobosanchez.nyajsw.lifecycle;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ExitAction;
import io.github.jacobosanchez.nyajsw.config.LifecycleConfig;
import io.github.jacobosanchez.nyajsw.launch.ApplicationLauncher;
import io.github.jacobosanchez.nyajsw.launch.LaunchException;
import io.github.jacobosanchez.nyajsw.launch.RunningApplication;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;
import io.github.jacobosanchez.nyajsw.probe.ProbeContext;
import io.github.jacobosanchez.nyajsw.probe.ProbeResult;
import io.github.jacobosanchez.nyajsw.probe.ProbeRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * readiness check and the timers of the {@link Scheduler}. No decision depends on how threads
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
 * would watch it. A stop request while a terminated, unready invocation is ending cancels its
 * restart.
 *
 * <p>Still to come in this phase: the stop strategies (P3.4; until then a stop waits {@code
 * wrapper.shutdown.timeout} for the application to end by itself, as in console mode, where it
 * receives the same Ctrl+C, and then terminates its process tree).
 */
public final class Supervisor {

    /** How long a terminated application may take to be gone before the supervisor gives up on it. */
    static final Duration AFTER_TERMINATION = Duration.ofSeconds(10);

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

    private sealed interface Event permits Start, Exited, StopRequested, ReadinessChecked, TimerFired {
    }

    private record Start() implements Event {
    }

    /** Invocation {@code invocation} ended; {@code error} is set when its exit could not be observed. */
    private record Exited(int invocation, Integer code, Throwable error) implements Event {
    }

    private record StopRequested() implements Event {
    }

    private record ReadinessChecked(int invocation, ProbeResult result) implements Event {
    }

    private record TimerFired(long id) implements Event {
    }

    private final LifecycleConfig lifecycle;
    private final ExitActionResolver exits;
    private final RestartPolicy restarts;
    private final ApplicationLauncher launcher;
    private final ProbeRunner readiness;
    private final Executor probeExecutor;
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
    private Integer lastExitCode;
    /** What the last readiness check of the current invocation found. */
    private String lastReadiness;
    /** The timers of the current state, by id; a fired id that is missing is ignored. */
    private final Map<Long, PendingTimer> timers = new HashMap<>();
    private long timersStarted;

    private record PendingTimer(Runnable action, Scheduler.Timer handle) {
    }

    /**
     * @param lifecycle     the supervision settings
     * @param launcher      starts each invocation
     * @param readiness     the readiness probes and how often to check them
     * @param probeExecutor runs the readiness checks, which may block, off the supervisor's thread;
     *                      one check runs at a time per invocation, but a check that hangs must not
     *                      hold up the next invocation's, so give each check its own thread (virtual
     *                      threads suit) rather than a single shared one
     * @param scheduler     runs the timers and tells the time
     * @param log           the wrapper log
     * @param listener      told about every change of state
     */
    public Supervisor(LifecycleConfig lifecycle, ApplicationLauncher launcher, ProbeRunner readiness,
            Executor probeExecutor, Scheduler scheduler, WrapperLog log, StateListener listener) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.exits = new ExitActionResolver(lifecycle);
        this.restarts = new RestartPolicy(lifecycle.maxFailedInvocations(), lifecycle.successfulInvocationTime());
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.probeExecutor = Objects.requireNonNull(probeExecutor, "probeExecutor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.log = Objects.requireNonNull(log, "log");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Supervises the application until supervision is over. Call it once. A stop requested before
     * the call ends supervision without starting anything.
     *
     * @return how supervision ended
     * @throws InterruptedException when the calling thread is interrupted; the application is left
     *                              as it is, so use {@link #requestStop()} to end supervision instead
     */
    public Outcome run() throws InterruptedException {
        start();
        while (!state.isTerminal()) {
            handle(events.take());
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
            case Exited exited -> exited(exited);
            case StopRequested request -> stopRequested();
            case ReadinessChecked checked -> readinessChecked(checked);
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
        application = launched;
        int current = invocation;
        launched.onExit().whenComplete((code, error) -> events.add(new Exited(current, code, error)));
        if (!lifecycle.startupTimeout().isZero()) { // 0: no limit, as in the Tanuki format YAJSW files inherit
            startTimer(lifecycle.startupTimeout(), this::notReady);
        }
        checkReadiness();
    }

    /** Checks the probes on the probe executor; the result comes back as an event, whatever happens. */
    private void checkReadiness() {
        int current = invocation;
        ProbeContext context = new ProbeContext(application);
        probeExecutor.execute(() -> {
            // ProbeRunner already turns a probe's exception into a failure; this is the backstop for
            // anything else (an Error included), so that the supervisor always hears back.
            ProbeResult result = ProbeResult.fail("the readiness check did not complete");
            try {
                result = readiness.check(context);
            } catch (RuntimeException e) {
                result = ProbeResult.fail("the readiness check threw " + e.getClass().getName());
            } finally {
                events.add(new ReadinessChecked(current, result));
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
            return;
        }
        lastReadiness = result.detail();
        log.debug(() -> application.name() + " is not ready yet: " + result.detail());
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
        application.terminateTree();
        if (!restarts.mayRestart()) {
            giveUp(name);
            return;
        }
        moveTo(State.RESTART_WAIT);
        log.status("restarting " + lifecycle.restartDelay().toSeconds() + " s after " + name + " has ended"
                + " (failed invocations in a row: " + restarts.failures() + " of " + restarts.maxFailedInvocations()
                + ")");
        startTimer(AFTER_TERMINATION, this::abandonUnready);
    }

    /** A terminated invocation that was not ready is still running: restarting beside it would run the application twice. */
    private void abandonUnready() {
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
            moveTo(state == State.STOPPING ? State.STOPPED : State.FAILED);
            return;
        }
        int code = exited.code();
        lastExitCode = code;
        switch (state) {
            case STOPPING -> {
                log.debug(() -> name + " ended while stopping; no exit action applies");
                moveTo(State.STOPPED);
            }
            case STARTING, RUNNING -> applyExitAction(name, code);
            case RESTART_WAIT -> { // the terminated invocation that was not ready has ended
                cancelTimers();
                startTimer(lifecycle.restartDelay(), this::launch);
            }
            default -> throw new IllegalStateException(name + " exited while " + state);
        }
    }

    private void applyExitAction(String name, int code) {
        restarts.ended(scheduler.now().minus(launchedAt));
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
            case STOPPING, STOPPED, FAILED -> log.debug("stop requested again; already " + state);
        }
    }

    private void beginStop() {
        moveTo(State.STOPPING);
        Duration timeout = lifecycle.shutdownTimeout();
        log.status("stopping: waiting up to " + timeout.toSeconds() + " s for " + application.name() + " to end");
        startTimer(timeout, this::terminate);
    }

    private void terminate() {
        log.status(application.name() + " did not end within " + lifecycle.shutdownTimeout().toSeconds()
                + " s; terminating it");
        application.terminateTree();
        startTimer(AFTER_TERMINATION, this::abandon);
    }

    private void abandon() {
        log.error(application.name() + " is still running " + AFTER_TERMINATION.toSeconds()
                + " s after it was terminated; giving up on it");
        application = null;
        moveTo(State.STOPPED);
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
        log.debug(() -> "state " + from + " -> " + target);
        try {
            listener.changed(from, target);
        } catch (RuntimeException e) {
            log.error("a state listener failed on " + from + " -> " + target, e);
        }
    }
}
