package com.azur.launcher.account;

import com.azur.launcher.net.HostPolicy;
import com.azur.launcher.net.HttpUtils;
import com.azur.launcher.net.ProxyConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** JSON over https to the site origin only (the central {@link HostPolicy} decides; loopback http for local tests). No redirects. */
public final class HttpSiteApi implements SiteApi {

    private static final Gson GSON = new Gson();

    private final String origin;
    private final HttpClient client = HttpClient.newBuilder().proxy(ProxyConfig.selector())
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    /** @param origin for example {@code https://example.test}, no trailing slash */
    public HttpSiteApi(String origin) {
        this.origin = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }

    @Override
    public Started start(String deviceName, String publicKey) throws SiteException {
        JsonObject o = post("/api/launcher/device/start", Map.of("name", deviceName, "pubkey", publicKey));
        return new Started(str(o, "device_code"), str(o, "user_code"), str(o, "fingerprint"), str(o, "verify_url"),
                num(o, "expires_in", 600), num(o, "interval", 3));
    }

    @Override
    public Polled poll(String deviceCode) throws SiteException {
        JsonObject o = post("/api/launcher/device/poll", Map.of("device_code", deviceCode));
        boolean approved = "approved".equals(str(o, "status"));
        return new Polled(approved, str(o, "device_id"), str(o, "refresh_token"), str(o, "nick"));
    }

    @Override
    public Ticket ticket(String deviceId, String refresh, String server) throws SiteException {
        JsonObject o = post("/api/launcher/ticket", Map.of("device_id", deviceId, "refresh_token", refresh, "server", server));
        return new Ticket(str(o, "ticket"), str(o, "nick"), str(o, "refresh_token"));
    }

    @Override
    public String rotate(String deviceId, String refresh, String newPublicKey, String proof) throws SiteException {
        JsonObject o = post("/api/launcher/device/rotate",
                Map.of("device_id", deviceId, "refresh_token", refresh, "pubkey", newPublicKey, "proof", proof));
        return str(o, "refresh_token");
    }

    private JsonObject post(String path, Map<String, String> body) throws SiteException {
        String url = origin + path;
        if (!HttpUtils.downloader().policy().allows(url)) {
            throw new SiteException("site host is not allowed", null);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("User-Agent", HttpUtils.USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SiteException("no answer from the site", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SiteException("interrupted", e);
        }
        JsonObject json = parse(response.body());
        if (response.statusCode() / 100 != 2) {
            throw new SiteException(response.statusCode(), str(json, "error"));
        }
        return json;
    }

    private static JsonObject parse(String text) {
        try {
            var element = GSON.fromJson(text, com.google.gson.JsonElement.class);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static int num(JsonObject o, String key, int fallback) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
