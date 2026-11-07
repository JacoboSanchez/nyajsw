package io.github.jacobosanchez.nyajsw.platform.windows.jna;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinNT;

/**
 * {@code JOBOBJECT_EXTENDED_LIMIT_INFORMATION} (winnt.h), for {@code SetInformationJobObject} with
 * {@link Kernel32Jobs#JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS}; {@code jna-platform} does not
 * declare it (plan §4.4). The wrapper sets only {@link Kernel32Jobs#JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}.
 */
@Structure.FieldOrder({"BasicLimitInformation", "IoInfo", "ProcessMemoryLimit", "JobMemoryLimit",
        "PeakProcessMemoryUsed", "PeakJobMemoryUsed"})
public class JobObjectExtendedLimitInformation extends Structure {

    public JobObjectBasicLimitInformation BasicLimitInformation = new JobObjectBasicLimitInformation();
    public WinNT.IO_COUNTERS IoInfo = new WinNT.IO_COUNTERS();
    public BaseTSD.SIZE_T ProcessMemoryLimit = new BaseTSD.SIZE_T();
    public BaseTSD.SIZE_T JobMemoryLimit = new BaseTSD.SIZE_T();
    public BaseTSD.SIZE_T PeakProcessMemoryUsed = new BaseTSD.SIZE_T();
    public BaseTSD.SIZE_T PeakJobMemoryUsed = new BaseTSD.SIZE_T();

    /** Limits with only {@code limitFlags} set. */
    public JobObjectExtendedLimitInformation(int limitFlags) {
        BasicLimitInformation.LimitFlags = limitFlags;
    }
}
