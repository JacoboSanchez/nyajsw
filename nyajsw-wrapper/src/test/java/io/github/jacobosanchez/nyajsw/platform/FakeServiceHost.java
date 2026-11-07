package io.github.jacobosanchez.nyajsw.platform;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ServiceHost} without a service manager, for tests of what runs as the service: {@link
 * #run} runs the body on the calling thread, {@link #stop} plays the manager's stop request from
 * another, and every report is recorded. It keeps the host's rules: {@code START_PENDING} first,
 * a stop request only while {@code RUNNING} (Windows refuses it in a pending state), {@code
 * STOP_PENDING} before the body hears of it, no {@code starting} after {@code running} nor either
 * after a stop, and {@code STOPPED} last. Wait hints are recorded as given.
 */
public final class FakeServiceHost implements ServiceHost {

    /** A status the body reported, or the host reported for it. */
    public record Report(ServiceState state, Duration waitHint) {
    }

    private final String serviceName;
    private final Object lock = new Object();
    private final List<Report> reports = new ArrayList<>();
    private final CountDownLatch running = new CountDownLatch(1);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private ServiceBody body;
    private boolean started;
    private boolean stopping;
    private ServiceExit exit;

    public FakeServiceHost(String serviceName) {
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
    }

    @Override
    public void run(ServiceBody service) {
        synchronized (lock) {
            if (body != null) {
                throw new IllegalStateException("a service host runs one service");
            }
            body = Objects.requireNonNull(service, "service");
            record(ServiceState.START_PENDING, Duration.ZERO);
        }
        ServiceExit result = ServiceExit.UNEXPECTED_ERROR;
        try {
            result = service.run(new Context());
        } finally {
            synchronized (lock) {
                exit = result;
                record(ServiceState.STOPPED, Duration.ZERO);
            }
            running.countDown(); // a service that ended before it ran
            stopped.countDown();
        }
    }

    /**
     * Asks the service to stop, as the service manager does on {@code control}.
     *
     * @return whether a running service was asked; false, as Windows refuses it, unless the last
     *         state reported is {@code RUNNING}
     */
    public boolean stop(ServiceControl control) {
        ServiceBody target;
        synchronized (lock) {
            if (reports.isEmpty() || reports.get(reports.size() - 1).state() != ServiceState.RUNNING) {
                return false;
            }
            stopping = true;
            record(ServiceState.STOP_PENDING, Duration.ZERO);
            target = body;
        }
        target.stop(control);
        return true;
    }

    /** Waits until the service has reported {@code RUNNING}, or ended. */
    public boolean awaitRunning(Duration timeout) throws InterruptedException {
        return running.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Waits until {@code STOPPED} has been reported. */
    public boolean awaitStopped(Duration timeout) throws InterruptedException {
        return stopped.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** The reports so far, in order. */
    public List<Report> reports() {
        synchronized (lock) {
            return List.copyOf(reports);
        }
    }

    /** The states reported so far, in order. */
    public List<ServiceState> states() {
        return reports().stream().map(Report::state).toList();
    }

    /** How the service ended, once {@code STOPPED} is reported. */
    public Optional<ServiceExit> exit() {
        synchronized (lock) {
            return Optional.ofNullable(exit);
        }
    }

    private void record(ServiceState state, Duration waitHint) {
        reports.add(new Report(state, waitHint));
    }

    private final class Context implements ServiceContext {

        @Override
        public String serviceName() {
            return serviceName;
        }

        @Override
        public void starting(Duration waitHint) {
            synchronized (lock) {
                if (!started && !stopping && exit == null) {
                    record(ServiceState.START_PENDING, waitHint);
                }
            }
        }

        @Override
        public void running() {
            synchronized (lock) {
                if (!stopping && exit == null) {
                    record(ServiceState.RUNNING, Duration.ZERO);
                    started = true;
                }
            }
            running.countDown();
        }

        @Override
        public void stopping(Duration waitHint) {
            synchronized (lock) {
                if (exit == null) {
                    stopping = true;
                    record(ServiceState.STOP_PENDING, waitHint);
                }
            }
        }
    }
}
