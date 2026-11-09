package io.jsl.nyajsw.log;

import java.nio.file.AccessDeniedException;
import java.util.Objects;

/**
 * The hint for a file the wrapper may not write (plan P6.3): a service often runs as an account
 * other than the one that installed it ({@code LocalSystem}, {@code NetworkService}, a domain
 * account), which may not write where the configuration says, so the message names that account
 * and the key that chose the place.
 */
public final class WriteAccess {

    private WriteAccess() {
    }

    /**
     * {@code "; the wrapper runs as 'SYSTEM', which may not write there: give it write access, or
     * change wrapper.logfile"} when {@code failure} is a denied access (also as its cause), else an
     * empty string.
     *
     * @param key the configuration key that names the file
     */
    public static String hint(Throwable failure, String key) {
        Objects.requireNonNull(key, "key");
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AccessDeniedException) {
                return "; the wrapper runs as '" + System.getProperty("user.name", "?") + "', which may not write"
                        + " there: give it write access, or change " + key;
            }
        }
        return "";
    }
}
