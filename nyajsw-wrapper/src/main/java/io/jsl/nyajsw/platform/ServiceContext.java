package io.jsl.nyajsw.platform;

import java.time.Duration;

/**
 * How a {@link ServiceBody} reports its progress to the service manager. Each pending report is a
 * promise to report again within its wait hint; the manager takes a service that does not as hung.
 * A host raises a shorter wait hint to {@link #MIN_WAIT_HINT}. Reports fail with a {@link
 * ServiceException} when the manager refuses them.
 */
public interface ServiceContext {

    /** The shortest wait hint a pending report gets, so that the next one is never due at once (plan §4.4). */
    Duration MIN_WAIT_HINT = Duration.ofSeconds(2);

    /** The service's name, as the service manager started it. */
    String serviceName();

    /** Still starting ({@link ServiceState#START_PENDING}), accepting no requests; the next report is due within {@code waitHint}. */
    void starting(Duration waitHint);

    /** Started ({@link ServiceState#RUNNING}): from now on it accepts stop requests, also at system shutdown. */
    void running();

    /** Stopping ({@link ServiceState#STOP_PENDING}), accepting no requests; the next report is due within {@code waitHint}. */
    void stopping(Duration waitHint);
}
