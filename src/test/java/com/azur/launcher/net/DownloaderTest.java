package com.azur.launcher.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Downloader against a local fake server (loopback, plain http through the test policy). */
class DownloaderTest {

    private HttpServer server;
    private String base;
    private byte[] body;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        body = new byte[200_000];
        new Random(7).nextBytes(body);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Downloader client(int attempts, int readTimeoutMs) {
        return new Downloader(HostPolicy.forTests(Set.of("127.0.0.1")), attempts, 1, 2000, readTimeoutMs);
    }

    private static String sha1(byte[] data) throws Exception {
        return Hashes.toHex(MessageDigest.getInstance("SHA-1").digest(data));
    }

    private static String sha256(byte[] data) throws Exception {
        return Hashes.toHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private void serve(String path, byte[] data) {
        server.createContext(path, ex -> {
            hits.incrementAndGet();
            String range = ex.getRequestHeaders().getFirst("Range");
            if (Objects.nonNull(range)) {
                int from = Integer.parseInt(range.substring("bytes=".length(), range.indexOf('-')));
                ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (data.length - 1) + "/" + data.length);
                ex.sendResponseHeaders(206, data.length - from);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(data, from, data.length - from);
                }
            } else {
                ex.sendResponseHeaders(200, data.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(data);
                }
            }
        });
    }

    @Test
    void downloadsAndVerifiesSha1Sha256AndSize(@TempDir Path dir) throws Exception {
        serve("/ok", body);
        Path target = dir.resolve("a/b.bin");
        client(2, 5000).download(base + "/ok", target, new Checks(sha1(body), sha256(body), body.length));
        assertArrayEquals(body, Files.readAllBytes(target));
        assertFalse(Files.exists(dir.resolve("a/b.bin.part")));
        int before = hits.get();
        client(2, 5000).download(base + "/ok", target, new Checks(sha1(body), null, body.length));
        assertEquals(before, hits.get(), "a valid file is not downloaded again");
    }

    @Test
    void corruptedContentIsRejectedAndLeavesNothing(@TempDir Path dir) throws Exception {
        byte[] bad = body.clone();
        bad[100] ^= 1;
        serve("/bad", bad);
        Path target = dir.resolve("bad.bin");
        assertThrows(IOException.class, () -> client(2, 5000).download(base + "/bad", target,
                new Checks(sha1(body), null, body.length)));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("bad.bin.part")));
        assertEquals(2, hits.get(), "retried once, then gave up");
    }

    @Test
    void wrongPinnedSha256IsRejectedEvenWhenSha1Matches(@TempDir Path dir) throws Exception {
        serve("/p", body);
        Path target = dir.resolve("p.bin");
        assertThrows(IOException.class, () -> client(1, 5000).download(base + "/p", target,
                new Checks(sha1(body), "0".repeat(64), 0)));
        assertFalse(Files.exists(target));
    }

    @Test
    void truncatedTransferIsResumedWithRange(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/t", ex -> {
            int n = calls.incrementAndGet();
            String range = ex.getRequestHeaders().getFirst("Range");
            if (n == 1) {
                ex.sendResponseHeaders(200, body.length);
                try {
                    ex.getResponseBody().write(body, 0, 50_000);
                    ex.getResponseBody().flush();
                    ex.close();
                } catch (IOException ignored) {
                    // the short body is the point of this case
                }
                return;
            }
            int from = Integer.parseInt(range.substring(6, range.indexOf('-')));
            ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (body.length - 1) + "/" + body.length);
            ex.sendResponseHeaders(206, body.length - from);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body, from, body.length - from);
            }
        });
        Path target = dir.resolve("t.bin");
        client(3, 5000).download(base + "/t", target, new Checks(sha1(body), null, body.length));
        assertArrayEquals(body, Files.readAllBytes(target));
        assertEquals(2, calls.get());
    }

    @Test
    void existingPartFileIsResumed(@TempDir Path dir) throws Exception {
        serve("/r", body);
        Path target = dir.resolve("r.bin");
        Files.write(dir.resolve("r.bin.part"), Arrays.copyOf(body, 70_000));
        client(2, 5000).download(base + "/r", target, new Checks(sha1(body), null, body.length));
        assertArrayEquals(body, Files.readAllBytes(target));
    }

    @Test
    void redirectToUnlistedHostIsRefused(@TempDir Path dir) throws Exception {
        server.createContext("/go", ex -> {
            ex.getResponseHeaders().add("Location", base.replace("127.0.0.1", "localhost") + "/ok");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        serve("/ok", body);
        Path target = dir.resolve("x.bin");
        IOException e = assertThrows(IOException.class, () -> client(3, 5000).download(base + "/go", target, Checks.NONE));
        assertInstanceOf(HostPolicy.BlockedHostException.class, e);
        assertFalse(Files.exists(target));
        assertEquals(0, hits.get(), "the unlisted host is never contacted");
    }

    @Test
    void redirectWithinAllowedHostsIsFollowed(@TempDir Path dir) throws Exception {
        server.createContext("/go", ex -> {
            ex.getResponseHeaders().add("Location", "/ok");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        serve("/ok", body);
        Path target = dir.resolve("y.bin");
        client(1, 5000).download(base + "/go", target, new Checks(sha1(body), null, 0));
        assertArrayEquals(body, Files.readAllBytes(target));
    }

    @Test
    void slowServerTimesOutAndFails(@TempDir Path dir) {
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(1500);
                ex.sendResponseHeaders(200, 1);
                ex.getResponseBody().write(1);
            } catch (Exception ignored) {
                // client gave up
            } finally {
                ex.close();
            }
        });
        Path target = dir.resolve("s.bin");
        assertThrows(IOException.class, () -> client(2, 200).download(base + "/slow", target, Checks.NONE));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("s.bin.part")));
    }

    @Test
    void getBytesReadsSmallResources() throws Exception {
        serve("/m", "hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("hello", client(1, 5000).getString(base + "/m"));
    }

    @Test
    void downloadManagerMergesSameTarget(@TempDir Path dir) throws Exception {
        serve("/ok", body);
        DownloadManager manager = new DownloadManager(3, null, null, client(2, 5000));
        Path same = dir.resolve("same.bin");
        List<DownloadManager.Task> tasks = List.of(
                new DownloadManager.Task(base + "/ok", same, sha1(body), body.length),
                new DownloadManager.Task(base + "/ok", same, sha1(body), body.length),
                new DownloadManager.Task(base + "/ok", dir.resolve("o.bin"), sha1(body), body.length));
        assertTrue(manager.downloadAll(tasks));
        assertEquals(2, hits.get());
    }

    @Test
    void mirrorIsTriedFirstAndOfficialIsTheFallback(@TempDir Path dir) throws Exception {
        server.createContext("/official", ex -> {
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/mirror", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        Checks checks = Checks.of(sha1(body), body.length);
        Path target = dir.resolve("f.bin");
        client(3, 2000).downloadFirst(List.of(base + "/mirror", base + "/official"), target, checks);
        assertArrayEquals(body, Files.readAllBytes(target));
        assertEquals(1, hits.get(), "a missing mirror costs one attempt, not three");
    }

    @Test
    void unreachableHostGivesAHumanMessage() {
        Downloader d = new Downloader(HostPolicy.forTests(Set.of("127.0.0.1")), 1, 1, 500, 500);
        IOException e = assertThrows(IOException.class, () -> d.getString("http://127.0.0.1:1/x"));
        assertInstanceOf(HostUnreachableException.class, e);
        assertTrue(e.getMessage().contains("127.0.0.1"), e.getMessage());
        assertFalse(e.getMessage().startsWith("Request failed"));
    }
}
