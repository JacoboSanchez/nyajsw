package io.github.jacobosanchez.nyajsw.probe;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import io.github.jacobosanchez.nyajsw.TestConfigs;
import io.github.jacobosanchez.nyajsw.config.ProbeConfig;
import io.github.jacobosanchez.nyajsw.config.StatusRanges;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The {@code http} probe against a local {@link HttpServer}. */
public class HttpProbeTest {

    private static final StatusRanges DEFAULT = StatusRanges.parse("200-399");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    /** Released at the end of each test, so that a handler that waits on it never outlives the test. */
    private CountDownLatch release;

    @BeforeMethod
    public void setUp() throws IOException {
        status.set(200);
        release = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool()); // a handler that waits must not hold up the others
        server.createContext("/health", exchange -> {
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/missing");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            await();
            exchange.close();
        });
        server.createContext("/stalling", exchange -> {
            exchange.sendResponseHeaders(200, 0); // chunked
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            await(); // headers sent, the body never ends
            exchange.close();
        });
        server.start();
    }

    private void await() {
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        release.countDown();
        server.stop(0);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private ProbeResult check(String path, StatusRanges accepted, Duration timeout) {
        return new HttpProbe(url(path), accepted, timeout).check(FakeRunningApplication.running());
    }

    @Test
    public void passesOnAnAcceptedStatus() {
        assertEquals(check("/health", DEFAULT, TIMEOUT),
                ProbeResult.pass("GET " + url("/health") + " answered 200"));
    }

    @Test
    public void failsOnAStatusThatIsNotAccepted() {
        status.set(503);

        assertEquals(check("/health", DEFAULT, TIMEOUT),
                ProbeResult.fail("GET " + url("/health") + " answered 503, not one of 200-399"));
        status.set(204);
        assertTrue(check("/health", StatusRanges.parse("200,204"), TIMEOUT).passed());
    }

    @Test
    public void aRedirectIsJudgedByItsOwnStatus() {
        assertTrue(check("/redirect", DEFAULT, TIMEOUT).passed(), "302 is accepted, /missing never requested");
        assertFalse(check("/redirect", StatusRanges.parse("200"), TIMEOUT).passed());
    }

    @Test
    public void failsWhenNothingAnswersInTime() {
        ProbeResult result = check("/slow", DEFAULT, Duration.ofMillis(300));

        assertFalse(result.passed());
        assertTrue(result.detail().startsWith("GET " + url("/slow") + " "), result.detail());
    }

    @Test(timeOut = 30_000)
    public void failsWhenTheBodyNeverEndsInsteadOfBlocking() {
        ProbeResult result = check("/stalling", DEFAULT, Duration.ofMillis(500));

        assertEquals(result, ProbeResult.fail("GET " + url("/stalling") + " did not complete within 0.500 s"));
    }

    @Test
    public void theRegistryBuildsItFromTheConfigurationAndLogsTheTargetWithoutCredentials() throws IOException {
        Path dir = Files.createTempDirectory("nyajsw-http-probe");
        try {
            String url = "http://user:pw@127.0.0.1:" + server.getAddress().getPort() + "/health?token=t0k3n";
            ProbeConfig config = TestConfigs.load(dir, "wrapper.readiness.http.url=" + url).probes();

            Probe probe = ProbeRegistry.defaults().create(List.of("http"), config).get(0).probe();
            ProbeResult result = probe.check(FakeRunningApplication.running());

            assertEquals(result, ProbeResult.pass("GET " + url("/health") + " answered 200"));
            assertEquals(expectThrows(IllegalArgumentException.class,
                    () -> HttpProbe.from(TestConfigs.load(dir).probes())).getMessage(),
                    "the http probe needs wrapper.readiness.http.url");
        } finally {
            TestConfigs.delete(dir);
        }
    }

    @Test
    public void failsWhenNothingListens() {
        URI url = url("/health");
        server.stop(0);

        ProbeResult result = new HttpProbe(url, DEFAULT, TIMEOUT).check(FakeRunningApplication.running());

        assertFalse(result.passed());
        assertTrue(result.detail().startsWith("GET " + url + " failed: "), result.detail());
    }

    @Test
    public void logMessagesNameTheTargetWithoutCredentialsOrQuery() {
        assertEquals(HttpProbe.describe(URI.create("https://user:pw@example.com:8443/a/b?token=x#f")),
                "https://example.com:8443/a/b");
        assertEquals(HttpProbe.describe(URI.create("http://example.com")), "http://example.com");
    }
}
