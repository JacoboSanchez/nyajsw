package io.github.jacobosanchez.nyajsw.platform;

/**
 * How a service ended, reported with {@code STOPPED}: {@code 0} for a service that stopped as
 * asked, any other code for one that failed. On Windows a failure is reported as {@code
 * ERROR_SERVICE_SPECIFIC_ERROR} (1066) with {@code code} as the service's own exit code, which the
 * Services console and {@code sc query} show (plan §4.4).
 *
 * @param code 0, or the service's own error code
 */
public record ServiceExit(int code) {

    /** The service stopped as asked. */
    public static final ServiceExit SUCCESS = new ServiceExit(0);

    /** The service ended on an error nothing foresaw; the code is that of the command line (D23). */
    public static final ServiceExit UNEXPECTED_ERROR = new ServiceExit(1);

    /** Whether the service failed. */
    public boolean failed() {
        return code != 0;
    }
}
