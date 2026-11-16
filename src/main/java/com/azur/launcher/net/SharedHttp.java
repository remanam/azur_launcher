package com.azur.launcher.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ONE {@link HttpClient} for every request to the site and the pack server: one connection pool, HTTP/2 and keep-alive,
 * so the slow TLS handshake (a proxy or a far route can cost 1-2 s) is paid once per host, not per request.
 * Rebuilt only when the proxy settings change; closed at exit.
 */
public final class SharedHttp {

    private static final AtomicInteger BUILT = new AtomicInteger();
    private static HttpClient client;

    private SharedHttp() {
    }

    /** The shared client: proxy of {@link ProxyConfig}, no redirects (the callers follow them by policy), 15 s connect. */
    public static synchronized HttpClient client() {
        if (Objects.isNull(client)) {
            client = ProxyConfig.configure(HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(15))).build();
            BUILT.incrementAndGet();
        }
        return client;
    }

    /** The proxy settings changed: the next request builds a client with the new ones. */
    static synchronized void invalidate() {
        HttpClient old = client;
        client = null;
        if (Objects.nonNull(old)) {
            old.close();
        }
    }

    /** Closes the pool (app exit). */
    public static void close() {
        invalidate();
    }

    /** How many clients were built since start (tests: it must stay 1). */
    public static int built() {
        return BUILT.get();
    }

    /** Opens the connection to {@code uri} in the background (TLS handshake), so the first real request is fast. Never throws. */
    public static void warmUp(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", HttpUtils.USER_AGENT).build();
        client().sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionally(e -> null);
    }
}
