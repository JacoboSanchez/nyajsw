package io.jsl.nyajsw.platform;

/** What runs as the service under a {@link ServiceHost}: started once, asked to stop at most a few times. */
public interface ServiceBody {

    /**
     * Runs the service until it has ended, on the service's own thread, reporting its progress
     * through {@code context}; the host reports {@code STOPPED} with the result once this returns or
     * throws.
     *
     * @return how the service ended
     */
    ServiceExit run(ServiceContext context);

    /**
     * Asks the running service to stop and returns at once: it is called on the service manager's
     * control thread, which must not be held up. The host has reported {@code STOP_PENDING} already,
     * which accepts no further requests, so a second call is not expected; it must still be
     * harmless, and this must not throw.
     */
    void stop(ServiceControl control);
}
