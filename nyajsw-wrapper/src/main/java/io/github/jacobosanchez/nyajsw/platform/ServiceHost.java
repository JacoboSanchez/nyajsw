package io.github.jacobosanchez.nyajsw.platform;

/**
 * Runs this process as the service the service manager started it for (plan §4.4, {@code
 * --service}): it connects to the service manager, runs a {@link ServiceBody} on the service's own
 * thread, passes the manager's stop requests on to it, and reports the service's status, {@code
 * STOPPED} always last.
 *
 * <p>The host reports {@link ServiceState#START_PENDING} as soon as it is connected, accepting no
 * requests, and {@link ServiceState#STOP_PENDING} at once when a stop request arrives, before the
 * body hears of it. Reports go one way: {@code starting} after {@code running}, or either after a
 * stop request, has no effect, so a stop is never undone.
 */
public interface ServiceHost {

    /**
     * Connects to the service manager and runs {@code body} as the service; returns once the
     * service has reported {@code STOPPED} and the manager has let go of the process, or once the
     * service can report nothing more (then it throws why, and the process should end). Call it
     * before any other work: the manager gives a starting service process only about 30 s to
     * connect.
     *
     * @throws ServiceException when the process cannot connect, such as when it was not started by
     *         the service manager
     * @throws PlatformException when the operating system's native interface cannot be loaded
     * @throws RuntimeException  what {@code body} threw, after {@code STOPPED} was reported for it
     */
    void run(ServiceBody body);
}
