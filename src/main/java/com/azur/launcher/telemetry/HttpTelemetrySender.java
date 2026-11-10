package com.azur.launcher.telemetry;

import com.azur.launcher.net.HttpUtils;
import com.azur.launcher.net.ProxyConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** https to the site origin only (central host policy), the launcher's proxy, no redirects, 5 s, one attempt. */
public final class HttpTelemetrySender implements TelemetrySender {

    private final String origin;

    public HttpTelemetrySender(String origin) {
        this.origin = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }

    @Override
    public void post(String path, String json) throws IOException {
        String url = origin + path;
        if (!HttpUtils.downloader().policy().allows(url)) {
            throw new IOException("host not allowed");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").header("User-Agent", HttpUtils.USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        try {
            int code = com.azur.launcher.net.SharedHttp.client().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code / 100 != 2) {
                throw new IOException("HTTP " + code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
