package io.github.jacobosanchez.nyajsw.platform.windows;

import io.github.jacobosanchez.nyajsw.platform.ServiceException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

/**
 * A Service Control Manager in miniature: {@link #runDispatcher} starts the service main on a thread
 * of its own, as Windows does, and returns once {@code STOPPED} is reported. As on Windows, it does
 * not return when the service main gives up without {@code STOPPED}: it blocks then, for {@value
 * #WAIT_SECONDS} s, so that a test sees whether the host stops waiting by itself. The controls given to
 * {@link #sendWhenRunning} are sent on the dispatcher's thread once {@code RUNNING} is reported.
 * Every report and every answer of the handler is recorded.
 */
final class FakeServiceHostApi implements ServiceHostApi {

    static final ScmHandle STATUS_HANDLE = new ScmHandle(42);
    private static final long WAIT_SECONDS = 10;

    final List<StatusReport> reports = Collections.synchronizedList(new ArrayList<>());
    final List<Integer> answers = Collections.synchronizedList(new ArrayList<>());
    private final BlockingQueue<StatusReport> queue = new LinkedBlockingQueue<>();
    private final String serviceName;
    private List<Integer> whenRunning = List.of();
    private RuntimeException dispatcherFailure;
    private RuntimeException registerFailure;
    private Predicate<StatusReport> refused = report -> false;
    private volatile IntUnaryOperator handler;
    volatile String registeredName;
    volatile String serviceThread;

    FakeServiceHostApi(String serviceName) {
        this.serviceName = serviceName;
    }

    FakeServiceHostApi sendWhenRunning(Integer... controls) {
        this.whenRunning = List.of(controls);
        return this;
    }

    FakeServiceHostApi failDispatcher(RuntimeException failure) {
        this.dispatcherFailure = failure;
        return this;
    }

    FakeServiceHostApi failRegistration(RuntimeException failure) {
        this.registerFailure = failure;
        return this;
    }

    /** Refuses the reports {@code refused} picks with error 6 (invalid handle). */
    FakeServiceHostApi refuse(Predicate<StatusReport> refused) {
        this.refused = refused;
        return this;
    }

    /** Sends {@code control} to the registered handler, as the dispatcher would, and returns its answer. */
    int send(int control) {
        return handler.applyAsInt(control);
    }

    @Override
    public void runDispatcher(Consumer<String> serviceMain) {
        if (dispatcherFailure != null) {
            throw dispatcherFailure;
        }
        Thread service = new Thread(() -> serviceMain.accept(serviceName), "fake-service-main");
        service.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (true) {
                StatusReport report = queue.poll(20, TimeUnit.MILLISECONDS);
                if (report == null) {
                    if (System.nanoTime() - deadline > 0) {
                        throw new AssertionError("the service did not stop within " + WAIT_SECONDS + " s: " + reports);
                    }
                    continue;
                }
                if (report.currentState() == ScmApi.SERVICE_RUNNING) {
                    for (int control : whenRunning) {
                        answers.add(send(control));
                    }
                    whenRunning = List.of();
                }
                if (report.currentState() == ScmApi.SERVICE_STOPPED) {
                    service.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Override
    public ScmHandle registerHandler(String name, IntUnaryOperator controlHandler) {
        serviceThread = Thread.currentThread().getName();
        if (registerFailure != null) {
            throw registerFailure;
        }
        registeredName = name;
        handler = controlHandler;
        return STATUS_HANDLE;
    }

    @Override
    public void setStatus(ScmHandle statusHandle, StatusReport report) {
        if (!statusHandle.equals(STATUS_HANDLE)) {
            throw new AssertionError("reported with " + statusHandle);
        }
        if (refused.test(report)) {
            throw new ServiceException("SetServiceStatus: the handle is invalid (Win32 error 6)", 6);
        }
        reports.add(report);
        queue.add(report);
    }
}
