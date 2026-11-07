package io.github.jacobosanchez.nyajsw.platform.windows;

import io.github.jacobosanchez.nyajsw.platform.Platform;
import io.github.jacobosanchez.nyajsw.platform.ServiceController;

import java.util.Objects;

/**
 * Windows: services through the Service Control Manager ({@link WindowsServiceController} on
 * {@link JnaScmApi}), run by the installing JVM's {@code javaw.exe} and classpath
 * ({@link ServiceCommandLine#forThisJvm}). Building it loads no native code.
 */
public final class WindowsPlatform implements Platform {

    private final String osName;
    private final ServiceController services;

    /** @param osName the host's {@code os.name}, such as {@code Windows Server 2022} */
    public WindowsPlatform(String osName) {
        this.osName = Objects.requireNonNull(osName, "osName");
        this.services = new WindowsServiceController(new JnaScmApi(), ServiceCommandLine.forThisJvm(), StateWait.REAL);
    }

    @Override
    public String name() {
        return osName;
    }

    @Override
    public ServiceController serviceController() {
        return services;
    }
}
