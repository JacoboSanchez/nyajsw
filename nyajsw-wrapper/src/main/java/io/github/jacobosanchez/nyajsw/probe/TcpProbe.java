package io.github.jacobosanchez.nyajsw.probe;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ProbeConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Objects;

/**
 * The {@code tcp} probe: it passes when a TCP connection to {@code wrapper.readiness.tcp.host} and
 * {@code wrapper.readiness.tcp.port} can be opened, and closes it at once. A host name is looked up
 * again on every check.
 */
public final class TcpProbe implements Probe {

    /** The type name the configuration uses. */
    public static final String TYPE = "tcp";

    /** How long one connection attempt may take. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final String host;
    private final int port;
    private final Duration timeout;

    /**
     * @param timeout how long one connection attempt may take, at least 1 ms
     */
    TcpProbe(String host, int port, Duration timeout) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.toMillis() < 1 || timeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("not a connect timeout: " + timeout);
        }
    }

    /**
     * The probe {@code config} describes.
     *
     * @throws IllegalArgumentException when {@code wrapper.readiness.tcp.port} is not set
     */
    static TcpProbe from(ProbeConfig config) {
        int port = config.tcpPort().orElseThrow(() -> new IllegalArgumentException("the " + TYPE + " probe needs "
                + ConfigKeys.READINESS_TCP_PORT.name()));
        return new TcpProbe(config.tcpHost(), port, CONNECT_TIMEOUT);
    }

    @Override
    public ProbeResult check(ProbeContext context) {
        String target = host + ":" + port;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            return ProbeResult.pass("connected to " + target);
        } catch (IOException e) {
            // The class says enough (refused, timed out, unknown host); the message adds the target again.
            return ProbeResult.fail("cannot connect to " + target + ": " + e.getClass().getSimpleName());
        }
    }
}
