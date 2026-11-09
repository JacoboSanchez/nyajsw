package io.jsl.nyajsw.probe;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.jsl.nyajsw.TestConfigs;
import io.jsl.nyajsw.config.ProbeConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.testng.SkipException;
import org.testng.annotations.Test;

public class TcpProbeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    public void passesWhileSomethingListensOnThePort() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            TcpProbe probe = new TcpProbe("127.0.0.1", server.getLocalPort(), TIMEOUT);

            assertEquals(probe.check(FakeRunningApplication.running()),
                    ProbeResult.pass("connected to 127.0.0.1:" + server.getLocalPort()));
        }
    }

    @Test
    public void failsWhenNothingListens() throws IOException {
        int port;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = server.getLocalPort(); // free again once closed
        }

        ProbeResult result = new TcpProbe("127.0.0.1", port, TIMEOUT).check(FakeRunningApplication.running());

        assertFalse(result.passed());
        assertTrue(result.detail().startsWith("cannot connect to 127.0.0.1:" + port + ": "), result.detail());
    }

    @Test
    public void failsForAHostThatCannotBeFound() {
        try {
            InetAddress.getByName("nyajsw-no-such-host.invalid");
            throw new SkipException("this resolver answers for names that do not exist");
        } catch (UnknownHostException expected) {
            // as it should
        }
        ProbeResult result = new TcpProbe("nyajsw-no-such-host.invalid", 80, TIMEOUT)
                .check(FakeRunningApplication.running());

        assertFalse(result.passed());
        assertTrue(result.detail().startsWith("cannot connect to nyajsw-no-such-host.invalid:80: "), result.detail());
    }

    @Test
    public void theRegistryBuildsItFromTheConfiguration() throws IOException {
        Path dir = Files.createTempDirectory("nyajsw-tcp-probe");
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ProbeConfig config = TestConfigs.load(dir, "wrapper.readiness.tcp.port=" + server.getLocalPort()).probes();

            Probe probe = ProbeRegistry.defaults().create(List.of("tcp"), config).get(0).probe();

            assertEquals(probe.check(FakeRunningApplication.running()),
                    ProbeResult.pass("connected to 127.0.0.1:" + server.getLocalPort()));
        } finally {
            TestConfigs.delete(dir);
        }
    }

    @Test
    public void theTimeoutMustFitAConnectAttempt() {
        expectThrows(IllegalArgumentException.class, () -> new TcpProbe("h", 1, Duration.ZERO));
        expectThrows(IllegalArgumentException.class, () -> new TcpProbe("h", 1, Duration.ofDays(30)));
    }
}
