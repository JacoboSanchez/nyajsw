package io.github.jacobosanchez.nyajsw;

/**
 * Root of the nyajsw exception hierarchy. Every failure the wrapper reports on purpose (bad
 * configuration, launch failure, service or platform error) is a subclass, so the command-line entry
 * point can map it to an exit code and a readable message instead of a stack trace.
 *
 * <p>Unchecked: these failures are reported once at the top, not handled at every call site.
 */
public class NyajswException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NyajswException(String message) {
        super(message);
    }

    public NyajswException(String message, Throwable cause) {
        super(message, cause);
    }
}
