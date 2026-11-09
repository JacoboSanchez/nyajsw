package io.jsl.nyajsw.platform.windows;

import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.Winsvc;

import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

/**
 * {@link ServiceHostApi} through {@code jna-platform}'s {@code Advapi32} (its Unicode entry points).
 * The native library is loaded by the first call, not when this class is built.
 */
final class JnaServiceHostApi implements ServiceHostApi {

    /** The name of the thread the service manager runs the service on, as thread dumps show it. */
    static final String SERVICE_THREAD = "nyajsw-service";

    // The service manager calls these through the native pointers JNA made for them, which live only as long
    // as the callback objects: so they are referenced from here for the life of the process. This is the one
    // static state the wrapper allows (AGENTS.md §4.4); a process runs one service.
    private static volatile Winsvc.SERVICE_MAIN_FUNCTION serviceMainCallback;
    private static volatile Winsvc.HandlerEx handlerCallback;

    @Override
    public void runDispatcher(Consumer<String> serviceMain) {
        Winsvc.SERVICE_MAIN_FUNCTION callback = new Winsvc.SERVICE_MAIN_FUNCTION() {
            @Override
            public void callback(int argc, Pointer argv) {
                serviceMain.accept(argc > 0 && argv != null ? argv.getWideStringArray(0, argc)[0] : "");
            }
        };
        // A daemon thread, detached once the service has ended, so it never holds up the JVM's exit.
        Native.setCallbackThreadInitializer(callback, new CallbackThreadInitializer(true, true, SERVICE_THREAD));
        serviceMainCallback = callback;
        // The table ends with an empty entry, so it is allocated as one contiguous array.
        Winsvc.SERVICE_TABLE_ENTRY[] table = (Winsvc.SERVICE_TABLE_ENTRY[]) new Winsvc.SERVICE_TABLE_ENTRY().toArray(2);
        table[0].lpServiceName = ""; // an own-process service is told its name in argv[0]
        table[0].lpServiceProc = callback;
        if (!Advapi32.INSTANCE.StartServiceCtrlDispatcher(table)) {
            throw JnaScmApi.failure("StartServiceCtrlDispatcher", Native.getLastError());
        }
    }

    @Override
    public ScmHandle registerHandler(String serviceName, IntUnaryOperator handler) {
        Winsvc.HandlerEx callback = new Winsvc.HandlerEx() {
            @Override
            public int callback(int control, int eventType, Pointer eventData, Pointer context) {
                return handler.applyAsInt(control);
            }
        };
        handlerCallback = callback;
        Winsvc.SERVICE_STATUS_HANDLE handle = Advapi32.INSTANCE.RegisterServiceCtrlHandlerEx(serviceName, callback, null);
        if (handle == null || handle.getPointer() == null) {
            throw JnaScmApi.failure("RegisterServiceCtrlHandlerEx", Native.getLastError());
        }
        return new ScmHandle(Pointer.nativeValue(handle.getPointer()));
    }

    @Override
    public void setStatus(ScmHandle statusHandle, StatusReport report) {
        Winsvc.SERVICE_STATUS status = new Winsvc.SERVICE_STATUS();
        status.dwServiceType = WinNT.SERVICE_WIN32_OWN_PROCESS;
        status.dwCurrentState = report.currentState();
        status.dwControlsAccepted = report.controlsAccepted();
        status.dwWin32ExitCode = report.win32ExitCode();
        status.dwServiceSpecificExitCode = report.serviceSpecificExitCode();
        status.dwCheckPoint = report.checkPoint();
        status.dwWaitHint = JnaScmApi.dword(report.waitHint());
        if (!Advapi32.INSTANCE.SetServiceStatus(new Winsvc.SERVICE_STATUS_HANDLE(new Pointer(statusHandle.value())),
                status)) {
            throw JnaScmApi.failure("SetServiceStatus", Native.getLastError());
        }
    }
}
