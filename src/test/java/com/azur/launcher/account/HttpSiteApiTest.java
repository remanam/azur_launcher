package com.azur.launcher.account;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The real HTTP client against a fake site on loopback: the device flow end to end, wire format, error mapping. */
class HttpSiteApiTest {
    private HttpServer server;
    private HttpSiteApi api;
    private final List<String> bodies = new ArrayList<>();
    private int polls;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(ex.getRequestURI().getPath() + " " + body);
            int status = 200;
            String out;
            switch (ex.getRequestURI().getPath()) {
                case "/api/launcher/device/start" -> out = "{\"device_code\":\"dc\",\"user_code\":\"ABCD-EFGH\",\"fingerprint\":\"aaaa-bbbb-cccc-dddd\","
                        + "\"verify_url\":\"https://example.test/link?code=ABCDEFGH\",\"expires_in\":600,\"interval\":3}";
                case "/api/launcher/device/poll" -> out = polls++ == 0 ? "{\"status\":\"pending\"}"
                        : "{\"status\":\"approved\",\"device_id\":\"d1\",\"refresh_token\":\"r1\",\"nick\":\"art\"}";
                case "/api/launcher/device/login" -> {
                    String pw = com.google.gson.JsonParser.parseString(body).getAsJsonObject().get("password").getAsString();
                    status = pw.equals("pa\"ss\\w<o>rd&é") ? 200 : 401;
                    out = status == 200 ? "{\"device_id\":\"d9\",\"refresh_token\":\"r9\",\"nick\":\"art\"}" : "{\"error\":\"bad_credentials\"}";
                }
                case "/api/launcher/ticket" -> {
                    status = body.contains("\"r1\"") ? 200 : 401;
                    out = status == 200 ? "{\"ticket\":\"t1\",\"nick\":\"art\",\"refresh_token\":\"r2\"}" : "{\"error\":\"bad_device\"}";
                }
                default -> {
                    status = 404;
                    out = "{\"error\":\"not_found\"}";
                }
            }
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        api = new HttpSiteApi("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void fullDeviceFlowOverHttp() throws Exception {
        SessionStorage storage = FakeSite.memory();
        long[] clock = {1000};
        SiteAuth auth = new SiteAuth(api, storage, () -> clock[0], ms -> clock[0] += ms);
        SiteAuth.Pending p = auth.begin("PC");
        assertEquals("ABCD-EFGH", p.started().userCode());
        assertTrue(bodies.get(0).contains("\"pubkey\":\"" + p.key().publicKeyBase64Url() + "\""));
        assertFalse(bodies.get(0).contains(p.key().seedBase64Url()), "the private key never goes to the site");
        SiteSession s = auth.await(p, new AtomicBoolean(), x -> { }).orElseThrow();
        assertEquals("art", s.nick());
        assertEquals("t1", auth.ticket(""));
        assertEquals("r2", storage.read().orElseThrow().refresh());
        String ticketCall = bodies.get(bodies.size() - 1);
        assertTrue(ticketCall.startsWith("/api/launcher/ticket ") && ticketCall.contains("\"device_id\":\"d1\"")
                && ticketCall.contains("\"refresh_token\":\"r1\""));
    }

    @Test
    void passwordLoginSendsTheExactPasswordWipesItAndStoresNoSecret() throws Exception {
        SessionStorage storage = FakeSite.memory();
        SiteAuth auth = new SiteAuth(api, storage, () -> 5L, ms -> { });
        char[] pw = "pa\"ss\\w<o>rd&é".toCharArray();
        SiteSession s = auth.login("  Art@Example.org ", pw, "PC", "en");
        assertEquals("d9", s.deviceId());
        assertEquals("r9", storage.read().orElseThrow().refresh());
        assertArrayEquals(new char[pw.length], pw, "the password array is wiped after the call");
        String call = bodies.get(0);
        assertTrue(call.startsWith("/api/launcher/device/login ") && call.contains("\"login\":\"Art@Example.org\""));
        assertFalse(call.contains(s.key().seedBase64Url()), "the private key never goes to the site");
        char[] wrong = "nope".toCharArray();
        SiteException e = assertThrows(SiteException.class, () -> auth.login("art", wrong, "PC", "ru"));
        assertEquals("bad_credentials", e.code());
        assertEquals("account.err.bad_credentials", SiteErrors.key(e));
        assertFalse(SiteErrors.dropsSession(e));
        assertArrayEquals(new char[4], wrong);
        assertFalse(e.getMessage().contains("nope"), "no password in an error message");
    }

    @Test
    void siteErrorsKeepTheirCode() {
        SiteException e = assertThrows(SiteException.class, () -> api.ticket("d1", "wrong", ""));
        assertEquals(401, e.status());
        assertEquals("bad_device", e.code());
        assertFalse(e.network());
        SiteException missing = assertThrows(SiteException.class, () -> api.rotate("d1", "r1", "k", "p"));
        assertEquals(404, missing.status());
    }

    @Test
    void noAnswerIsANetworkError() {
        server.stop(0);
        SiteException e = assertThrows(SiteException.class, () -> api.poll("dc"));
        assertTrue(e.network());
        assertEquals("account.err.network", SiteErrors.key(e));
    }

    @Test
    void aForeignHostIsRefusedByThePolicy() {
        SiteException e = assertThrows(SiteException.class, () -> new HttpSiteApi("https://evil.example").poll("dc"));
        assertTrue(e.network(), "blocked before any request");
    }
}
