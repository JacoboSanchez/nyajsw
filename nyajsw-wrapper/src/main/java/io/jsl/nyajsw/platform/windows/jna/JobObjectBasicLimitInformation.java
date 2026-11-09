package io.jsl.nyajsw.platform.windows.jna;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;

/**
 * {@code JOBOBJECT_BASIC_LIMIT_INFORMATION} (winnt.h), the first member of {@link
 * JobObjectExtendedLimitInformation}; {@code jna-platform} does not declare it (plan §4.4).
 */
@Structure.FieldOrder({"PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags", "MinimumWorkingSetSize",
        "MaximumWorkingSetSize", "ActiveProcessLimit", "Affinity", "PriorityClass", "SchedulingClass"})
public class JobObjectBasicLimitInformation extends Structure {

    /** {@code LARGE_INTEGER}. */
    public long PerProcessUserTimeLimit;
    /** {@code LARGE_INTEGER}. */
    public long PerJobUserTimeLimit;
    /** {@code DWORD}: which limits apply, the {@code JOB_OBJECT_LIMIT_*} flags. */
    public int LimitFlags;
    public BaseTSD.SIZE_T MinimumWorkingSetSize = new BaseTSD.SIZE_T();
    public BaseTSD.SIZE_T MaximumWorkingSetSize = new BaseTSD.SIZE_T();
    /** {@code DWORD}. */
    public int ActiveProcessLimit;
    public BaseTSD.ULONG_PTR Affinity = new BaseTSD.ULONG_PTR();
    /** {@code DWORD}. */
    public int PriorityClass;
    /** {@code DWORD}. */
    public int SchedulingClass;
}
