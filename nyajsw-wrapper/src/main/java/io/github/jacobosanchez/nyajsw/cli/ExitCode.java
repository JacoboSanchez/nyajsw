package io.github.jacobosanchez.nyajsw.cli;

import io.github.jacobosanchez.nyajsw.NyajswException;
import io.github.jacobosanchez.nyajsw.config.ConfigException;
import io.github.jacobosanchez.nyajsw.platform.ServiceException;
import io.github.jacobosanchez.nyajsw.platform.UnsupportedPlatformException;

import java.util.Objects;

/**
 * Exit codes of the wrapper's own commands (plan §3.2). Console mode returns the application's exit
 * code instead, which may coincide with these; the log says which one it was.
 */
public enum ExitCode {

    /** The command did what it was asked. */
    SUCCESS(0),

    /** An unexpected error, a launch failure or a command-line usage error. */
    UNEXPECTED_ERROR(1),

    /** The configuration could not be loaded or is invalid. */
    CONFIGURATION_ERROR(2),

    /** A Windows service operation failed. */
    SERVICE_OPERATION_FAILED(3),

    /** The command needs a platform this one is not, such as Windows services elsewhere. */
    UNSUPPORTED_PLATFORM(4);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    /** The process exit code. */
    public int code() {
        return code;
    }

    /**
     * The exit code for a command that failed with {@code failure} (D23). An operating system error
     * on a platform that has the operation is not an unsupported platform: the service manager's
     * refusals ({@link ServiceException}) end as {@link #SERVICE_OPERATION_FAILED}.
     */
    public static ExitCode of(NyajswException failure) {
        Objects.requireNonNull(failure, "failure");
        return switch (failure) {
            case ConfigException configuration -> CONFIGURATION_ERROR;
            case ServiceException service -> SERVICE_OPERATION_FAILED;
            case UnsupportedPlatformException unsupported -> UNSUPPORTED_PLATFORM;
            default -> UNEXPECTED_ERROR;
        };
    }
}
