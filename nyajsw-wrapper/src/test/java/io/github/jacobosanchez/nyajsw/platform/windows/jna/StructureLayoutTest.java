package io.github.jacobosanchez.nyajsw.platform.windows.jna;

import static org.testng.Assert.assertEquals;

import com.sun.jna.Native;
import com.sun.jna.Structure;

import java.util.List;

import org.testng.annotations.Test;

/** The hand-written structures have the layout of their winsvc.h declarations, on every OS. */
public class StructureLayoutTest {

    @Test
    public void delayedAutoStartInfoIsOneBool() {
        ServiceDelayedAutoStartInfo info = new ServiceDelayedAutoStartInfo(true);

        assertEquals(info.size(), 4);
        assertEquals(fieldOrder(ServiceDelayedAutoStartInfo.class), List.of("fDelayedAutostart"));
        info.write();
        assertEquals(info.getPointer().getInt(0), 1);
    }

    @Test
    public void preshutdownInfoIsOneDword() {
        ServicePreshutdownInfo info = new ServicePreshutdownInfo(75_000);

        assertEquals(info.size(), 4);
        assertEquals(fieldOrder(ServicePreshutdownInfo.class), List.of("dwPreshutdownTimeout"));
        info.write();
        assertEquals(info.getPointer().getInt(0), 75_000);
    }

    @Test
    public void lsaObjectAttributesHaveTheirLengthSet() {
        LsaObjectAttributes attributes = new LsaObjectAttributes();
        int pointer = Native.POINTER_SIZE;

        assertEquals(attributes.size(), pointer == 8 ? 48 : 24);
        assertEquals(attributes.Length, attributes.size());
        assertEquals(fieldOrder(LsaObjectAttributes.class), List.of("Length", "RootDirectory", "ObjectName",
                "Attributes", "SecurityDescriptor", "SecurityQualityOfService"));
    }

    @Test
    public void jobLimitsHaveTheirWinntLayoutWithOnlyTheFlagsSet() {
        JobObjectExtendedLimitInformation limits =
                new JobObjectExtendedLimitInformation(Kernel32Jobs.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
        boolean wide = Native.POINTER_SIZE == 8;

        assertEquals(limits.BasicLimitInformation.size(), wide ? 64 : 48);
        assertEquals(limits.size(), wide ? 144 : 112);
        assertEquals(fieldOrder(JobObjectBasicLimitInformation.class), List.of("PerProcessUserTimeLimit",
                "PerJobUserTimeLimit", "LimitFlags", "MinimumWorkingSetSize", "MaximumWorkingSetSize",
                "ActiveProcessLimit", "Affinity", "PriorityClass", "SchedulingClass"));
        assertEquals(fieldOrder(JobObjectExtendedLimitInformation.class), List.of("BasicLimitInformation", "IoInfo",
                "ProcessMemoryLimit", "JobMemoryLimit", "PeakProcessMemoryUsed", "PeakJobMemoryUsed"));
        limits.write();
        // LimitFlags follows the two LARGE_INTEGER time limits.
        assertEquals(limits.getPointer().getInt(16), 0x2000);
        assertEquals(limits.getPointer().getInt(0), 0);
    }

    private static List<String> fieldOrder(Class<? extends Structure> type) {
        return List.of(type.getAnnotation(Structure.FieldOrder.class).value());
    }
}
