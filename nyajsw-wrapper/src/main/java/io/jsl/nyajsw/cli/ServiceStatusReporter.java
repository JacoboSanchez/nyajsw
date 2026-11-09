package io.jsl.nyajsw.cli;

import io.jsl.nyajsw.lifecycle.Scheduler;
import io.jsl.nyajsw.lifecycle.State;
import io.jsl.nyajsw.lifecycle.Supervisor;
import io.jsl.nyajsw.log.WrapperLog;
import io.jsl.nyajsw.platform.ServiceContext;

import java.time.Duration;
import java.util.Objects;

/**
 * Tells the service manager what the supervisor is doing (plan §4.4, P6.2): {@code START_PENDING}
 * until the application is ready for the first time, {@code RUNNING} from then on, and {@code
 * STOP_PENDING} while it is being stopped. A restart of a running application does not show: the
 * service keeps running while the wrapper starts the application again, as it does under YAJSW.
 * Supervision that waits idle ({@code wrapper.control} keeps the wrapper alive after an exit whose
 * action is {@code STOP}) is a running service too, so that it can be stopped.
 *
 * <p>A pending state is reported again every half {@link #waitHint}, so the manager sees the
 * checkpoint move while the start or the stop goes on, but no longer than its limit (the wait of
 * {@code -t}, the wait of {@code -p}): a supervisor that is stuck then shows as a hung service
 * instead of one that is always about to start. The wait hint is twice the readiness interval, at
 * least {@link ServiceContext#MIN_WAIT_HINT}, so that a readiness check never trips the manager. A
 * report that fails is logged once and tried again after half the wait hint.
 */
final class ServiceStatusReporter implements Supervisor.StateListener {

    private enum Phase { STARTING, RUNNING, STOPPING, ENDED }

    private final ServiceContext context;
    private final Scheduler scheduler;
    private final Duration waitHint;
    private final Duration startLimit;
    private final Duration stopLimit;
    private final WrapperLog log;
    private Phase phase = Phase.STARTING;
    /** When the current phase began, on the scheduler's clock. */
    private Duration phaseBegan;
    private Scheduler.Timer repeat;
    private boolean failureLogged;

    /**
     * @param startLimit how long the start is reported as going on ({@link ServiceCommands#startTimeout})
     * @param stopLimit  how long the stop is reported as going on ({@link Supervisor#stopWait})
     */
    ServiceStatusReporter(ServiceContext context, Scheduler scheduler, Duration readinessInterval, Duration startLimit,
            Duration stopLimit, WrapperLog log) {
        this.context = Objects.requireNonNull(context, "context");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.waitHint = waitHint(readinessInterval);
        this.startLimit = Objects.requireNonNull(startLimit, "startLimit");
        this.stopLimit = Objects.requireNonNull(stopLimit, "stopLimit");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** The wait hint of the pending reports: twice {@code readinessInterval}, at least {@link ServiceContext#MIN_WAIT_HINT}. */
    static Duration waitHint(Duration readinessInterval) {
        Duration twice = Supervisor.sum(readinessInterval, readinessInterval);
        return twice.compareTo(ServiceContext.MIN_WAIT_HINT) < 0 ? ServiceContext.MIN_WAIT_HINT : twice;
    }

    /** Reports the start and keeps reporting it until the application is ready. */
    synchronized void begin() {
        enter(Phase.STARTING);
    }

    @Override
    public synchronized void changed(State from, State to) {
        switch (to) {
            case RUNNING, IDLE -> {
                if (phase == Phase.STARTING) {
                    enter(Phase.RUNNING);
                }
            }
            case STOPPING -> {
                if (phase != Phase.ENDED) {
                    enter(Phase.STOPPING);
                }
            }
            case STOPPED, FAILED -> end();
            case STARTING, RESTART_WAIT -> {
                // still starting before the first ready, running after it
            }
        }
    }

    /** No more reports: supervision is over, and the service host reports {@code STOPPED}. */
    synchronized void end() {
        phase = Phase.ENDED;
        cancelRepeat();
    }

    private void enter(Phase next) {
        phase = next;
        phaseBegan = scheduler.now();
        report();
    }

    /** Reports the current phase, and schedules the next report: of a pending phase, or of one that failed. */
    private void report() {
        cancelRepeat();
        boolean pending = phase == Phase.STARTING || phase == Phase.STOPPING;
        if (pending && overLimit()) {
            log.error("the service has been " + (phase == Phase.STARTING ? "starting" : "stopping") + " for longer than "
                    + limit().toSeconds() + " s; its progress is no longer reported, so Windows shows it as not"
                    + " responding");
            return;
        }
        boolean reported = true;
        try {
            switch (phase) {
                case STARTING -> context.starting(waitHint);
                case RUNNING -> context.running();
                case STOPPING -> context.stopping(waitHint);
                case ENDED -> {
                    return;
                }
            }
        } catch (RuntimeException e) {
            reported = false;
            if (!failureLogged) {
                failureLogged = true;
                log.error("cannot report the service's state to the Service Control Manager: " + e.getMessage());
            }
        }
        if (pending || !reported) {
            Phase scheduled = phase;
            repeat = scheduler.schedule(waitHint.dividedBy(2), () -> {
                synchronized (this) {
                    if (phase == scheduled) {
                        report();
                    }
                }
            });
        }
    }

    private boolean overLimit() {
        return scheduler.now().minus(phaseBegan).compareTo(limit()) > 0;
    }

    private Duration limit() {
        return phase == Phase.STARTING ? startLimit : stopLimit;
    }

    private void cancelRepeat() {
        if (repeat != null) {
            repeat.cancel();
            repeat = null;
        }
    }
}
