package io.github.jacobosanchez.nyajsw.lifecycle;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ExitAction;
import io.github.jacobosanchez.nyajsw.config.LifecycleConfig;
import io.github.jacobosanchez.nyajsw.launch.ApplicationLauncher;
import io.github.jacobosanchez.nyajsw.launch.LaunchException;
import io.github.jacobosanchez.nyajsw.launch.RunningApplication;
import io.github.jacobosanchez.nyajsw.log.WrapperLog;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Supervises the application (plan §4.6): starts it, decides what an exit means, and stops it on
 * request. The states and the moves between them are those of {@link State}.
 *
 * <p>Everything happens on the thread that calls {@link #run()}, one event at a time: the start, an
 * exit of the application, a stop request from any thread ({@link #requestStop()}) and the timers
 * of the {@link Scheduler}. No decision depends on how threads interleave, and tests replace the
 * process and the clock with fakes.
 *
 * <p>One timer counts at a time: every change of state cancels it, and a timer that fires after
 * being replaced is ignored. Work that needs its own cadence (readiness and liveness probes) posts
 * events instead of holding timers here.
 *
 * <p>An unexpected error while handling an event (a collaborator that throws, a broken invariant)
 * does not leave the application unsupervised: it is logged, a running application is terminated
 * and supervision ends {@link State#FAILED FAILED}.
 *
 * <p>What an exit means is decided only while the application runs as it should: once the
 * supervisor is {@link State#STOPPING STOPPING}, the application ends because the wrapper asked, so
 * {@code wrapper.on_exit.*} does not apply and it is never restarted.
 *
 * <p>Otherwise an exit is decided by {@link ExitActionResolver} ({@code wrapper.on_exit.*} with
 * {@code wrapper.control}), and a restart is subject to the {@link RestartPolicy}: an invocation that
 * cannot be started, or ends before {@code wrapper.successful_invocation_time}, is a failure, and
 * after {@code wrapper.max_failed_invocations} of them in a row supervision ends {@link
 * State#FAILED FAILED}. A launch error is retried like such a failure, whatever the exit actions
 * say, since there is no exit code to look up. The first start waits {@code wrapper.startup.delay}.
 *
 * <p>A stop request stops the application only when {@code wrapper.control} ties it to the wrapper
 * ({@code TIGHT}, {@code APPLICATION}); under {@code LOOSE} and {@code WRAPPER} supervision ends and
 * the application keeps running, as YAJSW documents. The safety nets do not follow that rule: after
 * an unexpected error, or when the exit of the application can no longer be observed, a live
 * application is terminated whatever {@code wrapper.control} says, since nothing would watch it.
 *
 * <p>Still to come in this phase: readiness probes between {@link State#STARTING STARTING} and
 * {@link State#RUNNING RUNNING} (P3.3, until then a started application counts as ready) and the
 * stop strategies (P3.4, until then a stop waits {@code wrapper.shutdown.timeout} for the
 * application to end by itself, as in console mode, where it receives the same Ctrl+C, and then
 * terminates its process tree).
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

    private sealed interface Event permits Start, Exited, StopRequested, TimerFired {
    }

    private record Start() implements Event {
    }

    /** Invocation {@code invocation} ended; {@code error} is set when its exit could not be observed. */
    private record Exited(int invocation, Integer code, Throwable error) implements Event {
    }

    private record StopRequested() implements Event {
    }

    private record TimerFired(long id) implements Event {
    }

    private final LifecycleConfig lifecycle;
    private final ExitActionResolver exits;
    private final RestartPolicy restarts;
    private final ApplicationLauncher launcher;
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
    /** The id of the one timer that still counts, 0 for none; earlier timers fire into the void. */
    private long timer;
    private long timersStarted;
    private Runnable onTimer;
    private Scheduler.Timer pendingTimer;

    /**
     * @param lifecycle the supervision settings
     * @param launcher  starts each invocation
     * @param scheduler runs the timers
     * @param log       the wrapper log
     * @param listener  told about every change of state
     */
    public Supervisor(LifecycleConfig lifecycle, ApplicationLauncher launcher, Scheduler scheduler, WrapperLog log,
            StateListener listener) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.exits = new ExitActionResolver(lifecycle);
        this.restarts = new RestartPolicy(lifecycle.maxFailedInvocations(), lifecycle.successfulInvocationTime());
        this.launcher = Objects.requireNonNull(launcher, "launcher");
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
            case TimerFired fired -> {
                if (fired.id() == timer) {
                    Runnable action = onTimer;
                    timer = 0;
                    onTimer = null;
                    action.run();
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
        moveTo(State.STARTING);
        RunningApplication launched;
        try {
            launched = launcher.launch(invocation);
        } catch (LaunchException e) {
            String name = ApplicationLauncher.nameOf(invocation);
            log.error("cannot start " + name + ": " + e.getMessage());
            restarts.notStarted();
            restartOrGiveUp(name);
            return;
        }
        launchedAt = scheduler.now();
        application = launched;
        int current = invocation;
        launched.onExit().whenComplete((code, error) -> events.add(new Exited(current, code, error)));
        moveTo(State.RUNNING);
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
            case RUNNING -> applyExitAction(name, code);
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
            log.error("giving up after " + name + ": " + restarts.failures() + " failed invocation(s) in a row reach "
                    + ConfigKeys.MAX_FAILED_INVOCATIONS.name() + " (limit " + restarts.maxFailedInvocations()
                    + "; an invocation fails when it cannot be started or"
                    + " ends within " + ConfigKeys.SUCCESSFUL_INVOCATION_TIME.name() + ", "
                    + lifecycle.successfulInvocationTime().toSeconds() + " s)");
            moveTo(State.FAILED);
            return;
        }
        moveTo(State.RESTART_WAIT);
        Duration delay = lifecycle.restartDelay();
        log.status("restarting in " + delay.toSeconds() + " s (failed invocations in a row: " + restarts.failures()
                + " of " + restarts.maxFailedInvocations() + ")");
        startTimer(delay, this::launch);
    }

    private void stopRequested() {
        switch (state) {
            case IDLE, RESTART_WAIT -> {
                log.status("stopping: no application is running");
                moveTo(State.STOPPED);
            }
            case RUNNING -> {
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
            // A launch completes within one event, so no event ever sees STARTING until probes arrive (P3.3).
            case STARTING -> throw new IllegalStateException("stop requested while " + state);
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
        timer = id;
        onTimer = action;
        pendingTimer = scheduler.schedule(delay, () -> events.add(new TimerFired(id)));
    }

    private void cancelTimer() {
        timer = 0;
        onTimer = null;
        Scheduler.Timer pending = pendingTimer;
        pendingTimer = null;
        if (pending != null) {
            try {
                pending.cancel();
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
        cancelTimer();
        state = target;
        log.debug(() -> "state " + from + " -> " + target);
        try {
            listener.changed(from, target);
        } catch (RuntimeException e) {
            log.error("a state listener failed on " + from + " -> " + target, e);
        }
    }
}
