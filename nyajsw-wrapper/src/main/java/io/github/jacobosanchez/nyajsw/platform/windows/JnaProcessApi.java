package io.github.jacobosanchez.nyajsw.platform.windows;

import io.github.jacobosanchez.nyajsw.platform.PlatformException;
import io.github.jacobosanchez.nyajsw.platform.windows.jna.JobObjectExtendedLimitInformation;
import io.github.jacobosanchez.nyajsw.platform.windows.jna.Kernel32Jobs;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;

/**
 * {@link ProcessApi} through the hand-written {@link Kernel32Jobs} binding and {@code jna-platform}'s
 * {@code Kernel32}. The native library is loaded by the first call, not when this class is built.
 */
final class JnaProcessApi implements ProcessApi {

    @Override
    public KernelHandle createJob() {
        return handle(Kernel32Jobs.INSTANCE.CreateJobObject(null, null), "CreateJobObject");
    }

    @Override
    public void setKillOnClose(KernelHandle job) {
        JobObjectExtendedLimitInformation limits =
                new JobObjectExtendedLimitInformation(Kernel32Jobs.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
        check(Kernel32Jobs.INSTANCE.SetInformationJobObject(nt(job),
                Kernel32Jobs.JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS, limits, limits.size()),
                "SetInformationJobObject(kill on close)");
    }

    @Override
    public KernelHandle openProcessForJob(long pid) {
        if (pid < 0 || pid > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("not a Windows process id: " + pid);
        }
        return handle(Kernel32.INSTANCE.OpenProcess(Kernel32Jobs.PROCESS_SET_QUOTA | Kernel32Jobs.PROCESS_TERMINATE,
                false, (int) pid), "OpenProcess");
    }

    @Override
    public void assign(KernelHandle job, KernelHandle process) {
        check(Kernel32Jobs.INSTANCE.AssignProcessToJobObject(nt(job), nt(process)), "AssignProcessToJobObject");
    }

    @Override
    public void terminate(KernelHandle job, int exitCode) {
        check(Kernel32Jobs.INSTANCE.TerminateJobObject(nt(job), exitCode), "TerminateJobObject");
    }

    @Override
    public void close(KernelHandle handle) {
        Kernel32.INSTANCE.CloseHandle(nt(handle));
    }

    private static WinNT.HANDLE nt(KernelHandle handle) {
        return new WinNT.HANDLE(new Pointer(handle.value()));
    }

    private static KernelHandle handle(WinNT.HANDLE handle, String call) {
        if (handle == null || handle.getPointer() == null) {
            throw failure(call);
        }
        return new KernelHandle(Pointer.nativeValue(handle.getPointer()));
    }

    private static void check(boolean succeeded, String call) {
        if (!succeeded) {
            throw failure(call);
        }
    }

    /** The last error of {@code call}, read at once, before any other native call can replace it. */
    private static PlatformException failure(String call) {
        return new PlatformException(Win32Failures.message(call, Native.getLastError(), false));
    }
}
