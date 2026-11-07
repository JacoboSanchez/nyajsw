package io.github.jacobosanchez.nyajsw.platform.windows;

import io.github.jacobosanchez.nyajsw.platform.Platform;
import io.github.jacobosanchez.nyajsw.platform.ServiceController;
import io.github.jacobosanchez.nyajsw.platform.ServiceHost;

import java.util.Objects;

/**
 * Windows: services through the Service Control Manager ({@link WindowsServiceController} on
 * {@link JnaScmApi} and {@link JnaLsaApi}), run by the installing JVM's {@code javaw.exe} and classpath
 * ({@link ServiceCommandLine#forThisJvm}), and hosted by {@link WindowsServiceHost} on {@link
 * JnaServiceHostApi}. Building it loads no native code.
 */
public final class WindowsPlatform implements Platform {

    private final String osName;
    private final ServiceController services;
    private final ServiceHost host;

    /** @param osName the host's {@code os.name}, such as {@code Windows Server 2022} */
    public WindowsPlatform(String osName) {
        this.osName = Objects.requireNonNull(osName, "osName");
        this.services = new WindowsServiceController(new JnaScmApi(), new JnaLsaApi(), ServiceCommandLine.forThisJvm(),
                StateWait.REAL);
        this.host = new WindowsServiceHost(new JnaServiceHostApi());
    }

    @Override
    public String name() {
        return osName;
    }

    @Override
    public ServiceController serviceController() {
        return services;
    }

    @Override
    public ServiceHost serviceHost() {
        return host;
    }
}
