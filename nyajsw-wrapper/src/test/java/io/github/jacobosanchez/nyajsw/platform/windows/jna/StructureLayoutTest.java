package io.github.jacobosanchez.nyajsw.platform.windows.jna;

import static org.testng.Assert.assertEquals;

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

    private static List<String> fieldOrder(Class<? extends Structure> type) {
        return List.of(type.getAnnotation(Structure.FieldOrder.class).value());
    }
}
