package io.jsl.nyajsw.platform.windows.jna;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * The job object functions of {@code kernel32}, which {@code jna-platform} does not declare (plan
 * §4.4, P6.4). Each returns {@code false} or a null handle on failure, with the reason in the last
 * error. The library is loaded when {@link #INSTANCE} is first read.
 */
public interface Kernel32Jobs extends StdCallLibrary {

    Kernel32Jobs INSTANCE = Native.load("kernel32", Kernel32Jobs.class, W32APIOptions.DEFAULT_OPTIONS);

    /** {@code JobObjectExtendedLimitInformation}, the information class of {@link JobObjectExtendedLimitInformation}. */
    int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9;

    /** {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}: the processes end when the last handle to the job is closed. */
    int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;

    /** {@code PROCESS_SET_QUOTA}: with {@code PROCESS_TERMINATE}, the access {@link #AssignProcessToJobObject} needs. */
    int PROCESS_SET_QUOTA = 0x0100;

    /** {@code PROCESS_TERMINATE}. */
    int PROCESS_TERMINATE = 0x0001;

    /** {@code CreateJobObjectW} without a name; {@code attributes} null for the default security. */
    WinNT.HANDLE CreateJobObject(Pointer attributes, String name);

    boolean SetInformationJobObject(WinNT.HANDLE job, int infoClass, Structure info, int length);

    boolean AssignProcessToJobObject(WinNT.HANDLE job, WinNT.HANDLE process);

    boolean TerminateJobObject(WinNT.HANDLE job, int exitCode);
}
