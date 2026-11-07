package io.github.jacobosanchez.nyajsw.platform.windows;

import io.github.jacobosanchez.nyajsw.config.Secret;
import io.github.jacobosanchez.nyajsw.platform.ServiceException;
import io.github.jacobosanchez.nyajsw.platform.windows.jna.ServiceDelayedAutoStartInfo;
import io.github.jacobosanchez.nyajsw.platform.windows.jna.ServicePreshutdownInfo;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.Winsvc;
import com.sun.jna.ptr.IntByReference;

import java.time.Duration;

/**
 * {@link ScmApi} through {@code jna-platform}'s {@code Advapi32} (its Unicode entry points) and the
 * structures of {@code platform.windows.jna}. The native library is loaded by the first call, not
 * when this class is built (see {@link io.github.jacobosanchez.nyajsw.platform.Platform}).
 */
final class JnaScmApi implements ScmApi {

    @Override
    public ScmHandle openManager(int access) {
        return handle(Advapi32.INSTANCE.OpenSCManager(null, null, access), "OpenSCManager");
    }

    @Override
    public ScmHandle openService(ScmHandle manager, String name, int access) {
        return handle(Advapi32.INSTANCE.OpenService(sc(manager), name, access), "OpenService");
    }

    @Override
    public ScmHandle createService(ScmHandle manager, ServiceRegistration registration, int access) {
        Winsvc.SC_HANDLE created = Advapi32.INSTANCE.CreateService(sc(manager), registration.name(),
                registration.displayName(), access, WinNT.SERVICE_WIN32_OWN_PROCESS,
                registration.scmStartType(), WinNT.SERVICE_ERROR_NORMAL, registration.binaryPath(),
                null, null, registration.dependencyList(), registration.account().orElse(null),
                registration.password().map(Secret::reveal).orElse(null));
        return handle(created, "CreateService");
    }

    @Override
    public void setDescription(ScmHandle service, String description) {
        Winsvc.SERVICE_DESCRIPTION info = new Winsvc.SERVICE_DESCRIPTION();
        info.lpDescription = description;
        check(Advapi32.INSTANCE.ChangeServiceConfig2(sc(service), Winsvc.SERVICE_CONFIG_DESCRIPTION, info),
                "ChangeServiceConfig2(description)");
    }

    @Override
    public void setDelayedAutoStart(ScmHandle service, boolean delayed) {
        check(Advapi32.INSTANCE.ChangeServiceConfig2(sc(service), Winsvc.SERVICE_CONFIG_DELAYED_AUTO_START_INFO,
                new ServiceDelayedAutoStartInfo(delayed)), "ChangeServiceConfig2(delayed auto-start)");
    }

    @Override
    public void setPreshutdownTimeout(ScmHandle service, Duration timeout) {
        check(Advapi32.INSTANCE.ChangeServiceConfig2(sc(service), Winsvc.SERVICE_CONFIG_PRESHUTDOWN_INFO,
                new ServicePreshutdownInfo(dword(timeout))), "ChangeServiceConfig2(pre-shutdown timeout)");
    }

    @Override
    public void deleteService(ScmHandle service) {
        check(Advapi32.INSTANCE.DeleteService(sc(service)), "DeleteService");
    }

    @Override
    public void startService(ScmHandle service) {
        check(Advapi32.INSTANCE.StartService(sc(service), 0, null), "StartService");
    }

    @Override
    public void stopService(ScmHandle service) {
        check(Advapi32.INSTANCE.ControlService(sc(service), Winsvc.SERVICE_CONTROL_STOP, new Winsvc.SERVICE_STATUS()),
                "ControlService(stop)");
    }

    @Override
    public ScmStatus queryStatus(ScmHandle service) {
        Winsvc.SERVICE_STATUS_PROCESS status = new Winsvc.SERVICE_STATUS_PROCESS();
        check(Advapi32.INSTANCE.QueryServiceStatusEx(sc(service), Winsvc.SC_STATUS_TYPE.SC_STATUS_PROCESS_INFO,
                status, status.size(), new IntByReference()), "QueryServiceStatusEx");
        return new ScmStatus(status.dwCurrentState, status.dwWin32ExitCode, status.dwServiceSpecificExitCode,
                status.dwWaitHint, status.dwProcessId);
    }

    @Override
    public void close(ScmHandle handle) {
        Advapi32.INSTANCE.CloseServiceHandle(sc(handle));
    }

    /** {@code timeout} in whole milliseconds as a positive {@code DWORD}, at most {@code Integer.MAX_VALUE}. */
    static int dword(Duration timeout) {
        if (timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(0, timeout.toMillis());
    }

    private static Winsvc.SC_HANDLE sc(ScmHandle handle) {
        Winsvc.SC_HANDLE sc = new Winsvc.SC_HANDLE();
        sc.setPointer(new Pointer(handle.value()));
        return sc;
    }

    private static ScmHandle handle(Winsvc.SC_HANDLE handle, String call) {
        if (handle == null || handle.getPointer() == null) {
            throw failure(call);
        }
        return new ScmHandle(Pointer.nativeValue(handle.getPointer()));
    }

    private static void check(boolean succeeded, String call) {
        if (!succeeded) {
            throw failure(call);
        }
    }

    /** The last error of {@code call}, read at once, before any other native call can replace it. */
    private static ServiceException failure(String call) {
        return failure(call, Native.getLastError());
    }

    /** The failure of {@code call} with Win32 error {@code code}, in the system's words and ours. */
    static ServiceException failure(String call, int code) {
        String text;
        try {
            text = Kernel32Util.formatMessage(code);
        } catch (RuntimeException e) { // no text for this code
            text = "";
        }
        return new ServiceException(call + ": " + Win32Errors.describe(code, text), code);
    }
}
