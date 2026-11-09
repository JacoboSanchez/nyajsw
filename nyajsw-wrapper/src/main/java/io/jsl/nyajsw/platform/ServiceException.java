package io.jsl.nyajsw.platform;

import io.jsl.nyajsw.NyajswException;

/**
 * The service manager refused or failed a service operation: install, remove, start, stop or
 * query (plan §4.4). The message says what was attempted and why, with the operating system's error
 * text and a hint for the common causes; {@link #errorCode()} is the Win32 error code, or 0 when the
 * failure was not an error of a call (a service that stopped while starting, a wait that timed out).
 * The command line ends with exit code 3 for it (D23).
 */
public class ServiceException extends NyajswException {

    private static final long serialVersionUID = 1L;

    private final int errorCode;

    public ServiceException(String message, int errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public ServiceException(String message, int errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** The Win32 error code, or 0 when there is none. */
    public int errorCode() {
        return errorCode;
    }
}
