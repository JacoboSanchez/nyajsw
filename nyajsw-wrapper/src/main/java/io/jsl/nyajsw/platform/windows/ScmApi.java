package io.jsl.nyajsw.platform.windows;

import io.jsl.nyajsw.platform.ServiceException;

import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.Winsvc;

import java.time.Duration;

/**
 * The Service Control Manager calls the wrapper makes, one method per Win32 call (or per info
 * level of {@code ChangeServiceConfig2}), in plain Java types so that the logic above can be tested
 * with mocks (plan §4.4, AGENTS.md §4.5). {@link JnaScmApi} is the implementation and the only
 * place that reads the last error: every method but {@link #close} throws a {@link ServiceException}
 * carrying the Win32 error code and its text when the call fails.
 */
interface ScmApi {

    // Access rights and service states, jna-platform's constants: inlined by the compiler, so callers
    // load no native code. Callers ask for no more access than they need (plan §4.4).
    int SC_MANAGER_CONNECT = Winsvc.SC_MANAGER_CONNECT;
    int SC_MANAGER_CREATE_SERVICE = Winsvc.SC_MANAGER_CREATE_SERVICE;
    int SERVICE_QUERY_CONFIG = Winsvc.SERVICE_QUERY_CONFIG;
    int SERVICE_CHANGE_CONFIG = Winsvc.SERVICE_CHANGE_CONFIG;
    int SERVICE_QUERY_STATUS = Winsvc.SERVICE_QUERY_STATUS;
    int SERVICE_START = Winsvc.SERVICE_START;
    int SERVICE_STOP = Winsvc.SERVICE_STOP;
    int DELETE = WinNT.DELETE;

    int SERVICE_STOPPED = Winsvc.SERVICE_STOPPED;
    int SERVICE_START_PENDING = Winsvc.SERVICE_START_PENDING;
    int SERVICE_STOP_PENDING = Winsvc.SERVICE_STOP_PENDING;
    int SERVICE_RUNNING = Winsvc.SERVICE_RUNNING;
    int SERVICE_CONTINUE_PENDING = Winsvc.SERVICE_CONTINUE_PENDING;
    int SERVICE_PAUSE_PENDING = Winsvc.SERVICE_PAUSE_PENDING;
    int SERVICE_PAUSED = Winsvc.SERVICE_PAUSED;

    /** {@code OpenSCManager} on the local machine's active database. */
    ScmHandle openManager(int access);

    /** {@code OpenService}. */
    ScmHandle openService(ScmHandle manager, String name, int access);

    /** {@code CreateService} for an own-process service that reports errors normally. */
    ScmHandle createService(ScmHandle manager, ServiceRegistration registration, int access);

    /** {@code ChangeServiceConfig2} with {@code SERVICE_CONFIG_DESCRIPTION}. */
    void setDescription(ScmHandle service, String description);

    /** {@code ChangeServiceConfig2} with {@code SERVICE_CONFIG_DELAYED_AUTO_START_INFO}. */
    void setDelayedAutoStart(ScmHandle service, boolean delayed);

    /** {@code ChangeServiceConfig2} with {@code SERVICE_CONFIG_PRESHUTDOWN_INFO}, in whole milliseconds. */
    void setPreshutdownTimeout(ScmHandle service, Duration timeout);

    /** {@code DeleteService}: removed once the last handle to it is closed and it has stopped. */
    void deleteService(ScmHandle service);

    /** {@code StartService} without arguments. */
    void startService(ScmHandle service);

    /** {@code ControlService} with {@code SERVICE_CONTROL_STOP}. */
    void stopService(ScmHandle service);

    /** {@code QueryServiceStatusEx} with {@code SC_STATUS_PROCESS_INFO}. */
    ScmStatus queryStatus(ScmHandle service);

    /** {@code CloseServiceHandle}; a failure is ignored, since it can only leak the handle. */
    void close(ScmHandle handle);
}
