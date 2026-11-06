package io.github.jacobosanchez.nyajsw.cli;

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
}
