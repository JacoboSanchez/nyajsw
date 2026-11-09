package io.jsl.nyajsw.platform.windows.jna;

import com.sun.jna.Structure;
import com.sun.jna.platform.win32.Winsvc;

/**
 * {@code SERVICE_PRESHUTDOWN_INFO} (winsvc.h), for {@code ChangeServiceConfig2} with {@code
 * SERVICE_CONFIG_PRESHUTDOWN_INFO}; {@code jna-platform} does not declare it (plan §4.4).
 */
@Structure.FieldOrder({"dwPreshutdownTimeout"})
public class ServicePreshutdownInfo extends Winsvc.ChangeServiceConfig2Info {

    /** {@code DWORD}: how long the service may take after the pre-shutdown notice, in milliseconds. */
    public int dwPreshutdownTimeout;

    public ServicePreshutdownInfo(int timeoutMillis) {
        this.dwPreshutdownTimeout = timeoutMillis;
    }
}
