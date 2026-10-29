package com.azur.launcher.auth;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** A local stand-in for Microsoft, Xbox and Minecraft services. Behaviour is switched through the fields. */
final class FakeAuthServer implements AutoCloseable {

    volatile String entitlementItems = "[{\"name\":\"game_minecraft\"},{\"name\":\"product_minecraft\"}]";
    volatile boolean refreshRejected;
    volatile int profileStatus = 200;
    final AtomicInteger requests = new AtomicInteger();

    private final HttpServer server;

    FakeAuthServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        route("/devicecode", exchange -> reply(exchange, 200,
                "{\"device_code\":\"dc\",\"user_code\":\"ABCD-EFGH\",\"verification_uri\":\"https://example.test/link\","
                        + "\"expires_in\":600,\"interval\":1}"));
        route("/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (form.contains("grant_type=refresh_token") && refreshRejected) {
                reply(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"expired\"}");
            } else {
                reply(exchange, 200, "{\"access_token\":\"msa-token\",\"refresh_token\":\"refresh-2\"}");
            }
        });
        route("/xbl", exchange -> reply(exchange, 200,
                "{\"Token\":\"xbl\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"hash\"}]}}"));
        route("/xsts", exchange -> reply(exchange, 200,
                "{\"Token\":\"xsts\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"hash\",\"xid\":\"42\"}]}}"));
        route("/mclogin", exchange -> reply(exchange, 200, "{\"access_token\":\"mc-token\",\"expires_in\":86400}"));
        route("/mcstore", exchange -> reply(exchange, 200, "{\"items\":" + entitlementItems + "}"));
        route("/license", exchange -> reply(exchange, 200, "{\"items\":[]}"));
        route("/profile", exchange -> reply(exchange, profileStatus,
                profileStatus == 200 ? "{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Alex\"}" : "{}"));
        server.start();
    }

    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    private void route(String path, Handler handler) {
        server.createContext(path, exchange -> {
            requests.incrementAndGet();
            handler.handle(exchange);
        });
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    MicrosoftAuthService service() {
        return new MicrosoftAuthService(AuthEndpoints.under(base()), AuthNetwork.forTests(2_000), 10L);
    }

    /** A service whose hosts refuse connections (nothing listens on the port). */
    static MicrosoftAuthService unreachableService() throws IOException {
        int port;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        return new MicrosoftAuthService(AuthEndpoints.under("http://127.0.0.1:" + port),
                AuthNetwork.forTests(1_000), 10L);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
