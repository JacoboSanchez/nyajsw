package io.github.jacobosanchez.nyajsw.platform.windows;

import io.github.jacobosanchez.nyajsw.platform.ServiceBody;
import io.github.jacobosanchez.nyajsw.platform.ServiceContext;
import io.github.jacobosanchez.nyajsw.platform.ServiceControl;
import io.github.jacobosanchez.nyajsw.platform.ServiceException;
import io.github.jacobosanchez.nyajsw.platform.ServiceExit;
import io.github.jacobosanchez.nyajsw.platform.ServiceHost;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * Runs this process as a Windows service through {@link ServiceHostApi} (plan §4.4): a thread of
 * its own, {@value #DISPATCHER_THREAD}, becomes the control dispatcher, and the service runs on the
 * thread the Service Control Manager starts for it.
 *
 * <p>That thread registers the control handler and reports {@code START_PENDING} at once, accepting
 * no controls, then runs the {@link ServiceBody}. {@code RUNNING} accepts {@code STOP}, {@code
 * PRESHUTDOWN} and {@code SHUTDOWN}; {@code PRESHUTDOWN} always, since plain {@code SHUTDOWN} leaves
 * only the seconds of {@code WaitToKillServiceTimeout}, while the pre-shutdown timeout set at
 * install covers the whole stop. Each of them is reported as {@code STOP_PENDING} on the control
 * thread before the body hears of it, so that a stop shows at once and is never undone by a late
 * {@code RUNNING}. {@code INTERROGATE} needs no answer of the service; other controls are refused as
 * not implemented. Reports are serialised and go one way: no {@code START_PENDING} after {@code
 * RUNNING}, nothing after {@code STOPPED}; the checkpoint counts up within a pending state, whose
 * wait hint is at least {@link #MIN_WAIT_HINT}. {@code STOPPED} is reported last, with {@code
 * ERROR_SERVICE_SPECIFIC_ERROR} and the body's code when it failed.
 *
 * <p>When the service thread cannot register its handler, or cannot report {@code STOPPED}, Windows
 * never lets the dispatcher return: {@link #run} then returns by itself and throws what went wrong,
 * leaving the dispatcher thread (a daemon) behind, so that the caller can log it and end the process.
 */
final class WindowsServiceHost implements ServiceHost {

    /** The name of the thread that runs the control dispatcher. */
    static final String DISPATCHER_THREAD = "nyajsw-service-dispatcher";

    /**
     * The wait hint of the reports the host makes on its own, the first {@code START_PENDING} and
     * the {@code STOP_PENDING} of a stop control: the body has this long to report its own.
     */
    static final Duration FIRST_WAIT_HINT = Duration.ofSeconds(10);

    /** The shortest wait hint reported, so that a body's next report is never due at once (plan §4.4). */
    static final Duration MIN_WAIT_HINT = Duration.ofSeconds(2);

    /** The controls a running service accepts. */
    static final int RUNNING_ACCEPTS = ServiceHostApi.SERVICE_ACCEPT_STOP | ServiceHostApi.SERVICE_ACCEPT_PRESHUTDOWN
            | ServiceHostApi.SERVICE_ACCEPT_SHUTDOWN;

    private final ServiceHostApi api;

    WindowsServiceHost(ServiceHostApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public void run(ServiceBody body) {
        Objects.requireNonNull(body, "body");
        Failures failures = new Failures();
        CountDownLatch over = new CountDownLatch(1);
        Thread dispatcher = new Thread(() -> {
            try {
                api.runDispatcher(name -> new Session(name, body, failures, over).serve());
            } catch (ServiceException e) {
                failures.primary(new ServiceException("cannot run as a service: " + e.getMessage(), e.errorCode(),
                        e));
            } catch (LinkageError e) {
                failures.primary(NativeLibrary.cannotLoad("run as a service", e));
            } catch (RuntimeException | Error e) {
                failures.primary(e);
            } finally {
                over.countDown();
            }
        }, DISPATCHER_THREAD);
        dispatcher.setDaemon(true);
        dispatcher.start();
        awaitUninterruptibly(over);
        failures.rethrow();
    }

    /** Waits for {@code latch}; an interrupt is kept for the caller, since the service has to be seen to its end. */
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * What went wrong on the dispatcher's, the service's and the control thread, rethrown by {@link
     * #run}: the dispatcher's or the body's failure first, with those of the control thread attached.
     */
    private static final class Failures {

        private Throwable first;
        private final List<Throwable> control = new ArrayList<>();

        /** A failure of the dispatcher or the body; the first one is thrown, later ones are attached. */
        synchronized void primary(Throwable failure) {
            if (first == null) {
                first = failure;
            } else if (first != failure) {
                first.addSuppressed(failure);
            }
        }

        synchronized void control(Throwable failure) {
            control.add(failure);
        }

        synchronized void rethrow() {
            Throwable thrown = first != null ? first : control.isEmpty() ? null : control.get(0);
            if (thrown == null) {
                return;
            }
            control.stream().filter(failure -> failure != thrown).forEach(thrown::addSuppressed);
            if (thrown instanceof RuntimeException e) {
                throw e;
            }
            if (thrown instanceof Error e) {
                throw e;
            }
            throw new IllegalStateException(thrown);
        }
    }

    /** One run of the service: its status, reported to the Service Control Manager. */
    private final class Session implements ServiceContext {

        private final String name;
        private final ServiceBody body;
        private final Failures failures;
        /** Counted down when the service can report nothing more, so that {@link #run} need not wait for the dispatcher. */
        private final CountDownLatch over;
        private final Object lock = new Object();
        /** The status handle; null until the handler is registered. Guarded by {@link #lock}. */
        private ScmHandle handle;
        /** The state last reported, 0 before the first report. Guarded by {@link #lock}. */
        private int state;
        /** Guarded by {@link #lock}. */
        private int checkPoint;
        /** Whether {@code RUNNING} has been reported; guarded by {@link #lock}. */
        private boolean started;
        /** Whether the service is stopping, asked to or of itself; guarded by {@link #lock}. */
        private boolean stopping;

        Session(String name, ServiceBody body, Failures failures, CountDownLatch over) {
            this.name = name;
            this.body = body;
            this.failures = failures;
            this.over = over;
        }

        /** The service thread's whole work: register, report, run the body, report {@code STOPPED}. */
        void serve() {
            ScmHandle registered;
            try {
                registered = api.registerHandler(name, this::control);
            } catch (ServiceException e) {
                giveUp(new ServiceException("cannot run the service '" + name + "': " + e.getMessage(), e.errorCode(),
                        e));
                return;
            } catch (RuntimeException | Error e) {
                giveUp(e);
                return;
            }
            synchronized (lock) {
                handle = registered;
            }
            ServiceExit exit = ServiceExit.UNEXPECTED_ERROR;
            try {
                starting(FIRST_WAIT_HINT);
                exit = Objects.requireNonNull(body.run(this), "the service's exit");
            } catch (Throwable e) { // whatever happened, STOPPED is reported
                failures.primary(e);
            } finally {
                try {
                    report(ScmApi.SERVICE_STOPPED, 0, Duration.ZERO, exit);
                } catch (RuntimeException | Error e) {
                    giveUp(e);
                }
            }
        }

        /** The service cannot go on reporting, so the dispatcher will not return: {@link #run} ends with {@code failure}. */
        private void giveUp(Throwable failure) {
            failures.primary(failure);
            over.countDown();
        }

        @Override
        public String serviceName() {
            return name;
        }

        @Override
        public void starting(Duration waitHint) {
            Objects.requireNonNull(waitHint, "waitHint");
            synchronized (lock) {
                if (!started && !stopping) {
                    report(ScmApi.SERVICE_START_PENDING, 0, waitHint, ServiceExit.SUCCESS);
                }
            }
        }

        @Override
        public void running() {
            synchronized (lock) {
                if (!stopping) {
                    report(ScmApi.SERVICE_RUNNING, RUNNING_ACCEPTS, Duration.ZERO, ServiceExit.SUCCESS);
                    started = true;
                }
            }
        }

        @Override
        public void stopping(Duration waitHint) {
            Objects.requireNonNull(waitHint, "waitHint");
            synchronized (lock) {
                stopping = true;
                report(ScmApi.SERVICE_STOP_PENDING, 0, waitHint, ServiceExit.SUCCESS);
            }
        }

        /** The control handler, on the dispatcher's thread: it must return quickly. */
        int control(int code) {
            ServiceControl control;
            switch (code) {
                case ServiceHostApi.SERVICE_CONTROL_STOP -> control = ServiceControl.STOP;
                case ServiceHostApi.SERVICE_CONTROL_PRESHUTDOWN -> control = ServiceControl.PRESHUTDOWN;
                case ServiceHostApi.SERVICE_CONTROL_SHUTDOWN -> control = ServiceControl.SHUTDOWN;
                case ServiceHostApi.SERVICE_CONTROL_INTERROGATE -> {
                    return ServiceHostApi.NO_ERROR; // the Service Control Manager knows the last status
                }
                default -> {
                    return ServiceHostApi.ERROR_CALL_NOT_IMPLEMENTED;
                }
            }
            synchronized (lock) {
                if (handle == null || state == ScmApi.SERVICE_STOPPED) {
                    return ServiceHostApi.NO_ERROR; // nothing runs that could stop
                }
                try {
                    stopping(FIRST_WAIT_HINT);
                } catch (RuntimeException e) {
                    failures.control(e); // the body still hears of the stop
                }
            }
            try {
                body.stop(control);
            } catch (RuntimeException e) {
                failures.control(e);
            }
            return ServiceHostApi.NO_ERROR;
        }

        /** Reports {@code newState}; nothing once {@code STOPPED} is reported. */
        private void report(int newState, int accepts, Duration waitHint, ServiceExit exit) {
            synchronized (lock) {
                if (state == ScmApi.SERVICE_STOPPED) {
                    return;
                }
                boolean pending = newState == ScmApi.SERVICE_START_PENDING || newState == ScmApi.SERVICE_STOP_PENDING;
                int nextCheckPoint = !pending ? 0 : newState == state ? checkPoint + 1 : 1;
                Duration hint = !pending ? Duration.ZERO : waitHint.compareTo(MIN_WAIT_HINT) < 0 ? MIN_WAIT_HINT : waitHint;
                api.setStatus(handle, new StatusReport(newState, accepts,
                        exit.failed() ? Win32Errors.SERVICE_SPECIFIC_ERROR : ServiceHostApi.NO_ERROR,
                        exit.failed() ? exit.code() : 0, nextCheckPoint, hint));
                state = newState;
                checkPoint = nextCheckPoint;
            }
        }
    }
}
