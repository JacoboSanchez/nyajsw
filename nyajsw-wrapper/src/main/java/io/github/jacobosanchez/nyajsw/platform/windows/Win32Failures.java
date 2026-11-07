package io.github.jacobosanchez.nyajsw.platform.windows;

import com.sun.jna.platform.win32.Kernel32Util;

/**
 * The message of a failed Win32 call, for the {@code Jna*} facades, which read the error code: the
 * call, the system's text for the code (from {@code FormatMessage}, so this loads native code) and
 * {@link Win32Errors}' words and hint.
 */
final class Win32Failures {

    private Win32Failures() {
    }

    /** Such as {@code "OpenService: Access is denied (Win32 error 5); run the command as Administrator"}. */
    static String message(String call, int code) {
        return message(call, code, true);
    }

    /**
     * As {@link #message(String, int)}, with the operator's hint only when {@code hint}: the hints
     * are written for service calls, and would mislead about others (a process that has ended).
     */
    static String message(String call, int code, boolean hint) {
        String text;
        try {
            text = Kernel32Util.formatMessage(code);
        } catch (RuntimeException e) { // no text for this code
            text = "";
        }
        return call + ": " + Win32Errors.describe(code, text, hint);
    }
}
