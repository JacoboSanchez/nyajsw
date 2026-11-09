package io.jsl.nyajsw.platform;

import io.jsl.nyajsw.NyajswException;

/**
 * The operating system refused or could not do what the wrapper asked of it. Where a Win32 call
 * failed, the message carries the error code and the system's text for it (plan §4.4).
 */
public class PlatformException extends NyajswException {

    private static final long serialVersionUID = 1L;

    public PlatformException(String message) {
        super(message);
    }

    public PlatformException(String message, Throwable cause) {
        super(message, cause);
    }
}
