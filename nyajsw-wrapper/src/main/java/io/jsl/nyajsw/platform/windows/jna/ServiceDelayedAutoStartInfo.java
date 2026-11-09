package io.jsl.nyajsw.platform.windows.jna;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Winsvc;

/**
 * {@code SERVICE_DELAYED_AUTO_START_INFO} (winsvc.h), for {@code ChangeServiceConfig2} with {@code
 * SERVICE_CONFIG_DELAYED_AUTO_START_INFO}; {@code jna-platform} does not declare it (plan §4.4).
 */
@Structure.FieldOrder({"fDelayedAutostart"})
public class ServiceDelayedAutoStartInfo extends Winsvc.ChangeServiceConfig2Info {

    /** {@code BOOL}: whether an automatic service starts shortly after the others. */
    public boolean fDelayedAutostart;

    public ServiceDelayedAutoStartInfo(boolean delayed) {
        this.fDelayedAutostart = delayed;
    }
}
