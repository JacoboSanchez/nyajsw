package io.github.jacobosanchez.nyajsw.platform.windows;

import com.sun.jna.platform.win32.WinError;

import java.util.Map;

/**
 * Turns a Win32 error code of a service call into text for the operator (plan §4.4): the system's
 * own message, the code, and for the errors an operator can act on, what to do. The codes are
 * {@code jna-platform}'s constants, inlined by the compiler, so this class loads no native code.
 */
final class Win32Errors {

    static final int ACCESS_DENIED = WinError.ERROR_ACCESS_DENIED;
    static final int DEPENDENT_SERVICES_RUNNING = WinError.ERROR_DEPENDENT_SERVICES_RUNNING;
    static final int SERVICE_REQUEST_TIMEOUT = WinError.ERROR_SERVICE_REQUEST_TIMEOUT;
    static final int SERVICE_ALREADY_RUNNING = WinError.ERROR_SERVICE_ALREADY_RUNNING;
    static final int INVALID_SERVICE_ACCOUNT = WinError.ERROR_INVALID_SERVICE_ACCOUNT;
    static final int SERVICE_DISABLED = WinError.ERROR_SERVICE_DISABLED;
    static final int SERVICE_DOES_NOT_EXIST = WinError.ERROR_SERVICE_DOES_NOT_EXIST;
    static final int SERVICE_CANNOT_ACCEPT_CTRL = WinError.ERROR_SERVICE_CANNOT_ACCEPT_CTRL;
    static final int SERVICE_NOT_ACTIVE = WinError.ERROR_SERVICE_NOT_ACTIVE;
    /** The service's own exit code ({@code dwServiceSpecificExitCode}) says why it stopped. */
    static final int SERVICE_SPECIFIC_ERROR = WinError.ERROR_SERVICE_SPECIFIC_ERROR;
    static final int PROCESS_ABORTED = WinError.ERROR_PROCESS_ABORTED;
    static final int SERVICE_DEPENDENCY_FAIL = WinError.ERROR_SERVICE_DEPENDENCY_FAIL;
    static final int SERVICE_LOGON_FAILED = WinError.ERROR_SERVICE_LOGON_FAILED;
    static final int SERVICE_MARKED_FOR_DELETE = WinError.ERROR_SERVICE_MARKED_FOR_DELETE;
    static final int SERVICE_EXISTS = WinError.ERROR_SERVICE_EXISTS;
    static final int DUPLICATE_SERVICE_NAME = WinError.ERROR_DUPLICATE_SERVICE_NAME;
    static final int NONE_MAPPED = WinError.ERROR_NONE_MAPPED;

    /** What each error is called when the system has no text for it, and what the operator can do. */
    private record Known(String name, String hint) {
    }

    private static final Map<Integer, Known> KNOWN = Map.ofEntries(
            Map.entry(ACCESS_DENIED, new Known("access is denied", "run the command as Administrator")),
            Map.entry(DEPENDENT_SERVICES_RUNNING, new Known("services that depend on this one are running",
                    "stop them first")),
            Map.entry(SERVICE_REQUEST_TIMEOUT, new Known("the service did not respond in time",
                    "see the wrapper log and the bootstrap log of the service")),
            Map.entry(SERVICE_ALREADY_RUNNING, new Known("the service is already running", "")),
            Map.entry(INVALID_SERVICE_ACCOUNT, new Known("the account name is invalid or does not exist",
                    "check wrapper.ntservice.account; a local account is written .\\name")),
            Map.entry(SERVICE_DISABLED, new Known("the service is disabled",
                    "change its start type, or install it with another wrapper.ntservice.starttype")),
            Map.entry(SERVICE_DOES_NOT_EXIST, new Known("the service is not installed", "")),
            Map.entry(SERVICE_CANNOT_ACCEPT_CTRL, new Known("the service cannot take that request now",
                    "it is starting or stopping; try again in a moment")),
            Map.entry(SERVICE_NOT_ACTIVE, new Known("the service is not running", "")),
            Map.entry(SERVICE_SPECIFIC_ERROR, new Known("the service ended with its own error code",
                    "see the wrapper log")),
            Map.entry(PROCESS_ABORTED, new Known("the service process ended unexpectedly",
                    "see the wrapper log and the bootstrap log of the service")),
            Map.entry(SERVICE_DEPENDENCY_FAIL, new Known("a service this one depends on did not start",
                    "check the services in wrapper.ntservice.dependency.<n>")),
            Map.entry(SERVICE_LOGON_FAILED, new Known("the service could not log on",
                    "check wrapper.ntservice.password and that the account may log on as a service")),
            Map.entry(SERVICE_MARKED_FOR_DELETE, new Known("the service is marked for deletion",
                    "close the Services console and any other program that has the service open, then try"
                            + " again")),
            Map.entry(SERVICE_EXISTS, new Known("a service of that name is installed already",
                    "remove it first (-r) or choose another wrapper.ntservice.name")),
            Map.entry(DUPLICATE_SERVICE_NAME, new Known("another service has that display name",
                    "choose another wrapper.ntservice.displayname")),
            Map.entry(NONE_MAPPED, new Known("no such account",
                    "check wrapper.ntservice.account: a local account is written .\\name, a domain account"
                            + " DOMAIN\\name")));

    private Win32Errors() {
    }

    /**
     * {@code code} described: {@code systemText} (the system's message, blank when it has none),
     * the code, and a hint where there is one, such as {@code "Access is denied (Win32 error 5);
     * run the command as Administrator"}.
     */
    static String describe(int code, String systemText) {
        Known known = KNOWN.get(code);
        String text = systemText == null ? "" : systemText.strip();
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty()) {
            text = known == null ? "error" : known.name();
        }
        String described = text + " (Win32 error " + code + ")";
        return known == null || known.hint().isEmpty() ? described : described + "; " + known.hint();
    }
}
