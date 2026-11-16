package com.azur.launcher.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Our own community service: registration by code, roles and whitelist status. This API only grants
 * access to our infrastructure; the player account stays a plain nickname.
 * Contract (see docs/API.md): {@code POST /v1/register}, {@code GET /v1/me}, {@code POST /v1/token/rotate}.
 */
public final class CommunityApi {

    /** The server the player may join, as the API describes it. */
    public record Server(String name, String address, int port) {
    }

    /** Where the pack can be fetched from (informational: the launcher takes the pack from the signed server list). */
    public record Pack(String url, String version) {
    }

    /** What the API knows about this player. {@code server} and {@code pack} are null right after registration. */
    public record Session(String token, String nick, String uuid, List<String> roles, boolean whitelisted,
                          Server server, Pack pack) {
    }

    /** The API answered with an error; {@code code} is its machine-readable reason ({@code code_invalid}, ...). */
    public static final class ApiException extends IOException {
        private final String code;
        private final int status;

        ApiException(String code, int status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }

    private static final Gson GSON = new Gson();

    private final String base;
    private final HttpClient http;

    /** @throws IllegalArgumentException when the address is not https (loopback http is allowed for local testing) */
    public CommunityApi(String baseUrl) {
        this(baseUrl, Duration.ofSeconds(15));
    }

    CommunityApi(String baseUrl, Duration timeout) {
        this.base = normalise(baseUrl);
        this.http = com.azur.launcher.net.ProxyConfig.configure(HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER))
                .build();
    }

    /** Registers the player with a code from the owner and returns the issued token. */
    public Session register(String code, String uuid, String nick) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("code", Objects.isNull(code) ? "" : code.trim());
        body.addProperty("uuid", Objects.isNull(uuid) ? "" : uuid.trim());
        body.addProperty("nick", Objects.isNull(nick) ? "" : nick.trim());
        JsonObject response = send("/v1/register", null, body);
        return new Session(required(response, "player_token"), nick, uuid, roles(response), true, null, null);
    }

    /** Current state of the player: roles, whitelist flag, server and pack address. */
    public Session me(String token) throws IOException {
        JsonObject response = send("/v1/me", token, null);
        return new Session(token, text(response, "nick"), text(response, "uuid"), roles(response),
                bool(response, "whitelisted"), server(response), pack(response));
    }

    /** Replaces the player token (after a leak, or when the API asks for a rotation). */
    public String rotate(String token) throws IOException {
        return required(send("/v1/token/rotate", token, new JsonObject()), "player_token");
    }

    private JsonObject send(String path, String token, JsonObject body) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "AzurLauncher");
        if (Objects.nonNull(token) && !token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        if (Objects.isNull(body)) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while talking to the community API", e);
        }
        JsonObject json = parseOrEmpty(response.body());
        if (response.statusCode() / 100 != 2) {
            String code = text(json, "error");
            throw new ApiException(code.isEmpty() ? "http_" + response.statusCode() : code, response.statusCode(),
                    code.isEmpty() ? "HTTP " + response.statusCode() : code);
        }
        return json;
    }

    private static String normalise(String baseUrl) {
        if (Objects.isNull(baseUrl) || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Community API address is empty");
        }
        String value = baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Community API address is not a URL: " + baseUrl, e);
        }
        String host = Objects.isNull(uri.getHost()) ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if (!https && !(loopback && "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Community API address must be https: " + baseUrl);
        }
        return value;
    }

    private static JsonObject parseOrEmpty(String body) {
        if (Objects.isNull(body) || body.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement element = GSON.fromJson(body, JsonElement.class);
            return Objects.nonNull(element) && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (JsonParseException e) {
            return new JsonObject();
        }
    }

    private static String text(JsonObject json, String name) {
        JsonElement element = json.get(name);
        return Objects.isNull(element) || element.isJsonNull() ? "" : element.getAsString();
    }

    private static String required(JsonObject json, String name) throws ApiException {
        String value = text(json, name);
        if (value.isBlank()) {
            throw new ApiException("bad_response", 200, "No " + name + " in the API response");
        }
        return value;
    }

    private static boolean bool(JsonObject json, String name) {
        JsonElement element = json.get(name);
        return Objects.nonNull(element) && !element.isJsonNull() && element.getAsBoolean();
    }

    private static List<String> roles(JsonObject json) {
        List<String> roles = new ArrayList<>();
        JsonElement element = json.get("roles");
        if (Objects.nonNull(element) && element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement role : array) {
                roles.add(role.getAsString());
            }
        }
        return List.copyOf(roles);
    }

    private static Server server(JsonObject json) {
        JsonElement element = json.get("server");
        if (Objects.isNull(element) || !element.isJsonObject()) {
            return null;
        }
        JsonObject server = element.getAsJsonObject();
        JsonElement port = server.get("port");
        return new Server(text(server, "name"), text(server, "address"),
                Objects.isNull(port) || port.isJsonNull() ? 25565 : port.getAsInt());
    }

    private static Pack pack(JsonObject json) {
        JsonElement element = json.get("pack");
        if (Objects.isNull(element) || !element.isJsonObject()) {
            return null;
        }
        JsonObject pack = element.getAsJsonObject();
        return new Pack(text(pack, "url"), text(pack, "version"));
    }
}
