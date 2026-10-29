package com.azur.launcher.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class HttpUtils {

    public static final String USER_AGENT = "Azur-Launcher/1.0.0";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_RETRY = 3;

    private HttpUtils() {
    }

    public static String getString(String url) throws IOException {
        return new String(getBytes(url), StandardCharsets.UTF_8);
    }

    public static byte[] getBytes(String url) throws IOException {
        return getBytes(url, secure(url));
    }

    private static byte[] getBytes(String sourceUrl, String url) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = open(url);
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + code + " -> " + url);
                }
                try (InputStream in = conn.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream(8192)) {
                    copy(in, out);
                    return out.toByteArray();
                }
            } catch (IOException e) {
                last = e;
                backoff(attempt);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw new IOException("Request failed: " + sourceUrl, last);
    }

    /** Downloads {@code url} to {@code target}, resuming and verifying size and SHA-1 when given. */
    public static void download(String url, Path target, String expectedSha1, long expectedSize) throws IOException {
        downloadOnce(secure(url), target, expectedSha1, expectedSize);
    }

    private static void downloadOnce(String url, Path target, String expectedSha1, long expectedSize)
            throws IOException {
        if (Files.exists(target) && isUpToDate(target, expectedSha1, expectedSize)) {
            return;
        }
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                if (attempt > 1) {
                    Files.deleteIfExists(target);
                }
                resumeDownload(url, target, expectedSize);
                verify(target, expectedSha1, expectedSize);
                return;
            } catch (IOException e) {
                last = e;
                backoff(attempt);
            }
        }
        Files.deleteIfExists(target);
        throw new IOException("Download failed: " + url, last);
    }

    private static boolean isUpToDate(Path target, String expectedSha1, long expectedSize) {
        try {
            long size = Files.size(target);
            if (expectedSize > 0 && size != expectedSize) {
                return false;
            }
            if (expectedSha1 != null && !expectedSha1.isBlank()) {
                return expectedSha1.equalsIgnoreCase(sha1(target));
            }
            return expectedSize > 0 && size > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void verify(Path target, String expectedSha1, long expectedSize) throws IOException {
        long size = Files.size(target);
        if (expectedSize > 0 && size != expectedSize) {
            throw new IOException("Size mismatch: " + target.getFileName()
                    + " (expected " + expectedSize + ", got " + size + ")");
        }
        if (expectedSha1 != null && !expectedSha1.isBlank() && !expectedSha1.equalsIgnoreCase(sha1(target))) {
            throw new IOException("SHA-1 mismatch: " + target.getFileName());
        }
    }

    private static void resumeDownload(String url, Path target, long expectedSize) throws IOException {
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }

        long existing = Files.exists(target) ? Files.size(target) : 0L;
        if (expectedSize > 0 && existing >= expectedSize) {
            Files.deleteIfExists(target);
            existing = 0L;
        }

        HttpURLConnection conn = open(url);
        try {
            if (existing > 0) {
                conn.setRequestProperty("Range", "bytes=" + existing + "-");
            }
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(target,
                             StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    copy(in, out);
                }
            } else if (code == HttpURLConnection.HTTP_OK) {
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                             StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    copy(in, out);
                }
            } else {
                throw new IOException("HTTP " + code + " -> " + url);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** All endpoints must use TLS: official hosts (Mojang, NeoForged) support https, so http is upgraded. */
    public static String secure(String url) {
        if (url != null && url.regionMatches(true, 0, "http://", 0, 7)) {
            return "https://" + url.substring(7);
        }
        return url;
    }

    public static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 is not available in this JVM", e);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static HttpURLConnection open(String url) throws IOException {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept-Encoding", "identity");
        return conn;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(4000L, 500L * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
