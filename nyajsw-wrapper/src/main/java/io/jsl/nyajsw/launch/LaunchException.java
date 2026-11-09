package io.jsl.nyajsw.launch;

import io.jsl.nyajsw.NyajswException;

/**
 * The application cannot be started as configured: the command line cannot be built, or the process
 * cannot be created. Messages show configuration values only in masked form.
 */
public class LaunchException extends NyajswException {

    private static final long serialVersionUID = 1L;

    public LaunchException(String message) {
        super(message);
    }

    public LaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
