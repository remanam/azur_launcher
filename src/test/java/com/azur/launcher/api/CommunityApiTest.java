package com.azur.launcher.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The community API client, against a local server that speaks the documented contract. */
class CommunityApiTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void registerThenMeParsesRolesWhitelistServerAndPack() throws Exception {
        AtomicReference<String> registerBody = new AtomicReference<>();
        AtomicReference<String> authHeader = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/register", exchange -> {
            registerBody.set(read(exchange.getRequestBody()));
            reply(exchange, 200, "{\"player_token\":\"tok-1\",\"nick\":\"Vasya\",\"roles\":[\"friend\"]}");
        });
        server.createContext("/v1/me", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, 200, "{\"nick\":\"Vasya\",\"uuid\":\"uuid-1\",\"roles\":[\"friend\",\"builder\"],"
                    + "\"whitelisted\":true,\"server\":{\"name\":\"Isles\",\"address\":\"mc.example\",\"port\":25565},"
                    + "\"pack\":{\"url\":\"https://pack.example\",\"invite\":\"azur1.abc\",\"version\":\"2026.10.29-1900\"}}");
        });
        server.start();
        CommunityApi api = new CommunityApi(base());

        CommunityApi.Session registered = api.register("code-1", "uuid-1", "Vasya");
        assertEquals("tok-1", registered.token());
        assertTrue(registerBody.get().contains("\"code\":\"code-1\""));
        assertTrue(registerBody.get().contains("\"uuid\":\"uuid-1\""));

        CommunityApi.Session session = api.me("tok-1");
        assertEquals("Bearer tok-1", authHeader.get());
        assertEquals("Vasya", session.nick());
        assertEquals(java.util.List.of("friend", "builder"), session.roles());
        assertTrue(session.whitelisted());
        assertEquals("mc.example", session.server().address());
        assertEquals(25565, session.server().port());
        assertEquals("azur1.abc", session.pack().invite());
        assertEquals("2026.10.29-1900", session.pack().version());
    }

    @Test
    void serverErrorBecomesAnApiExceptionWithItsCode() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/register", exchange -> reply(exchange, 403, "{\"error\":\"code_invalid\"}"));
        server.start();
        CommunityApi api = new CommunityApi(base());

        CommunityApi.ApiException error = assertThrows(CommunityApi.ApiException.class,
                () -> api.register("bad", "uuid", "nick"));
        assertEquals("code_invalid", error.code());
        assertEquals(403, error.status());
    }

    @Test
    void missingFieldsAreTolerated() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/me", exchange -> reply(exchange, 200, "{}"));
        server.start();

        CommunityApi.Session session = new CommunityApi(base()).me("tok");
        assertEquals("", session.nick());
        assertTrue(session.roles().isEmpty());
        assertFalse(session.whitelisted());
        assertTrue(session.server() == null);
        assertTrue(session.pack() == null);
    }

    @Test
    void plainHttpIsOnlyAllowedForLoopback() {
        assertThrows(IllegalArgumentException.class, () -> new CommunityApi("http://api.example.com"));
        assertThrows(IllegalArgumentException.class, () -> new CommunityApi(""));
        new CommunityApi("http://127.0.0.1:1");
        new CommunityApi("https://api.example.com/");
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
