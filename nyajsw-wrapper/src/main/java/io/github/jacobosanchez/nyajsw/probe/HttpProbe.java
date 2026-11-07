package io.github.jacobosanchez.nyajsw.probe;

import io.github.jacobosanchez.nyajsw.config.ConfigKeys;
import io.github.jacobosanchez.nyajsw.config.ProbeConfig;
import io.github.jacobosanchez.nyajsw.config.StatusRanges;
import io.github.jacobosanchez.nyajsw.log.Durations;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;

/**
 * The {@code http} probe: it passes when a {@code GET} of {@code wrapper.readiness.http.url}
 * answers with a status {@code wrapper.readiness.http.status} accepts. {@code
 * wrapper.readiness.http.timeout} bounds the whole exchange, connecting and the body included, so
 * that an application that sends its headers and then hangs fails the check instead of blocking it.
 * Redirects are not followed, so a {@code 3xx} is judged like any other status; the request goes
 * straight to the host over HTTP/1.1, whatever proxy the wrapper's JVM is told about, since the
 * target is usually the application on the same machine; {@code https} trusts what the wrapper's
 * JVM trusts. The body is discarded.
 *
 * <p>Log messages name the target without its user information, query and fragment, which can carry
 * credentials; user information is not sent either.
 */
public final class HttpProbe implements Probe {

    /** The type name the configuration uses. */
    public static final String TYPE = "http";

    private final URI url;
    private final String target;
    private final StatusRanges accepted;
    private final Duration timeout;
    private final HttpClient client;

    HttpProbe(URI url, StatusRanges accepted, Duration timeout) {
        this.url = Objects.requireNonNull(url, "url");
        this.target = describe(url);
        this.accepted = Objects.requireNonNull(accepted, "accepted");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).proxy(HttpClient.Builder.NO_PROXY).build();
    }

    /**
     * The probe {@code config} describes.
     *
     * @throws IllegalArgumentException when {@code wrapper.readiness.http.url} is not set
     */
    static HttpProbe from(ProbeConfig config) {
        String url = config.httpUrl().orElseThrow(() -> new IllegalArgumentException("the " + TYPE + " probe needs "
                + ConfigKeys.READINESS_HTTP_URL.name()));
        try {
            return new HttpProbe(new URI(url), config.httpStatus(), config.httpTimeout());
        } catch (URISyntaxException e) {
            // ProbeConfig has checked it; the message would quote the URL, so it is not passed on.
            throw new IllegalArgumentException(ConfigKeys.READINESS_HTTP_URL.name() + " is not a valid URL");
        }
    }

    /** {@code url} as log messages show it: scheme, host, port and path only. */
    static String describe(URI url) {
        String port = url.getPort() < 0 ? "" : ":" + url.getPort();
        String path = url.getRawPath() == null ? "" : url.getRawPath();
        return url.getScheme() + "://" + url.getHost() + port + path;
    }

    @Override
    public ProbeResult check(ProbeContext context) {
        HttpRequest request = HttpRequest.newBuilder(url).GET().timeout(timeout).build();
        // The request timeout ends with the headers; the wait below bounds the body as well.
        CompletableFuture<HttpResponse<Void>> response = client.sendAsync(request,
                HttpResponse.BodyHandlers.discarding());
        int status;
        try {
            status = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS).statusCode();
        } catch (TimeoutException e) {
            response.cancel(true);
            return ProbeResult.fail("GET " + target + " did not complete within " + Durations.seconds(timeout));
        } catch (ExecutionException e) {
            return ProbeResult.fail("GET " + target + " failed: " + reason(e.getCause()));
        } catch (InterruptedException e) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            return ProbeResult.fail("GET " + target + " was interrupted");
        }
        String detail = "GET " + target + " answered " + status;
        return accepted.contains(status) ? ProbeResult.pass(detail)
                : ProbeResult.fail(detail + ", not one of " + accepted);
    }

    /**
     * What went wrong, by class; for TLS also the message, the only clue to a trust-store problem,
     * which names certificates and host names but not the URL.
     */
    private static String reason(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof SSLException ssl && ssl.getMessage() != null) {
                return ssl.getClass().getSimpleName() + " (" + ssl.getMessage() + ")";
            }
        }
        return cause == null ? "unknown error" : cause.getClass().getSimpleName();
    }
}
