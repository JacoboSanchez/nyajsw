package io.jsl.nyajsw.platform.windows;

import io.jsl.nyajsw.platform.ServiceException;

import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.Winsvc;

import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

/**
 * The calls a process makes to run as a service, one method per Win32 call, in plain Java types so
 * that {@link WindowsServiceHost} can be tested without native code (plan §4.4, AGENTS.md §4.5).
 * {@link JnaServiceHostApi} is the implementation; every method throws a {@link ServiceException}
 * carrying the Win32 error code and its text when the call fails.
 */
interface ServiceHostApi {

    // jna-platform's constants, inlined by the compiler, so callers load no native code.
    int SERVICE_CONTROL_STOP = Winsvc.SERVICE_CONTROL_STOP;
    int SERVICE_CONTROL_INTERROGATE = Winsvc.SERVICE_CONTROL_INTERROGATE;
    int SERVICE_CONTROL_SHUTDOWN = Winsvc.SERVICE_CONTROL_SHUTDOWN;
    int SERVICE_CONTROL_PRESHUTDOWN = Winsvc.SERVICE_CONTROL_PRESHUTDOWN;

    int SERVICE_ACCEPT_STOP = Winsvc.SERVICE_ACCEPT_STOP;
    int SERVICE_ACCEPT_SHUTDOWN = Winsvc.SERVICE_ACCEPT_SHUTDOWN;
    int SERVICE_ACCEPT_PRESHUTDOWN = Winsvc.SERVICE_ACCEPT_PRESHUTDOWN;

    int NO_ERROR = WinError.NO_ERROR;
    int ERROR_CALL_NOT_IMPLEMENTED = WinError.ERROR_CALL_NOT_IMPLEMENTED;

    /**
     * {@code StartServiceCtrlDispatcher} with one entry for an own-process service: blocks until the
     * service has reported {@code STOPPED}. The service manager calls {@code serviceMain} on a
     * thread of its own with the service's name ({@code argv[0]}); control requests come on the
     * calling thread.
     */
    void runDispatcher(Consumer<String> serviceMain);

    /**
     * {@code RegisterServiceCtrlHandlerEx}: {@code handler} receives each control code and returns
     * a Win32 error code, {@link #NO_ERROR} for a control it handled.
     *
     * @return the status handle, which is never closed
     */
    ScmHandle registerHandler(String serviceName, IntUnaryOperator handler);

    /** {@code SetServiceStatus} for an own-process service. */
    void setStatus(ScmHandle statusHandle, StatusReport report);
}
