package com.azur.launcher.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * Verified downloads. Every hop passes {@link HostPolicy}; data goes to {@code <target>.part}, is checked
 * (size, SHA-1, SHA-256 as given) and moved atomically over the target. Interrupted transfers resume with
 * {@code Range}; corrupted ones restart from zero. Retries use linear backoff.
 */
public final class Downloader {

    public static final String USER_AGENT = "Azur-Launcher/1.0.0";
    private static final int MAX_REDIRECTS = 5;
    private static final String PART = ".part";

    private final HostPolicy policy;
    private final int attempts;
    private final long backoffMs;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public Downloader(HostPolicy policy, int attempts, long backoffMs, int connectTimeoutMs, int readTimeoutMs) {
        this.policy = policy;
        this.attempts = Math.max(1, attempts);
        this.backoffMs = backoffMs;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public static Downloader standard(HostPolicy policy) {
        return new Downloader(policy, 3, 500, 15_000, 30_000);
    }

    /** The same client with another attempt count (a mirror that misses must not cost three retries). */
    public Downloader withAttempts(int count) {
        return new Downloader(policy, count, backoffMs, connectTimeoutMs, readTimeoutMs);
    }

    /** Tries the URLs in order and returns the first answer; the last failure is thrown. */
    public String getStringFirst(List<String> urls) throws IOException {
        IOException last = null;
        for (int i = 0; i < urls.size(); i++) {
            try {
                return (i < urls.size() - 1 ? withAttempts(1) : this).getString(urls.get(i));
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("No URL") : last;
    }

    /** Downloads from the first URL that works; earlier (mirror) URLs get one attempt, the last one all of them. */
    public void downloadFirst(List<String> urls, Path target, Checks checks) throws IOException {
        IOException last = null;
        for (int i = 0; i < urls.size(); i++) {
            try {
                (i < urls.size() - 1 ? withAttempts(1) : this).download(urls.get(i), target, checks);
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("No URL") : last;
    }

    public HostPolicy policy() {
        return policy;
    }

    public String getString(String url) throws IOException {
        return new String(getBytes(url), StandardCharsets.UTF_8);
    }

    public byte[] getBytes(String url) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = open(url, 0);
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + code + " -> " + url);
                }
                try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    in.transferTo(out);
                    return out.toByteArray();
                }
            } catch (HostPolicy.BlockedHostException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                if (attempt < attempts) {
                    backoff(attempt);
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw last == null ? new IOException("Request failed: " + url)
                : HostUnreachableException.classify(new IOException("Request failed: " + url, last), url);
    }

    /** True when {@code target} exists and satisfies every given check. */
    public static boolean matches(Path target, Checks checks) {
        try {
            if (!Files.isRegularFile(target)) {
                return false;
            }
            long size = Files.size(target);
            if (checks.size() > 0 && size != checks.size()) {
                return false;
            }
            if (checks.has(checks.sha1()) && !checks.sha1().equalsIgnoreCase(Hashes.sha1(target))) {
                return false;
            }
            if (checks.has(checks.sha256()) && !checks.sha256().equalsIgnoreCase(Hashes.sha256(target))) {
                return false;
            }
            return checks.has(checks.sha1()) || checks.has(checks.sha256()) || (checks.size() > 0 && size > 0);
        } catch (IOException e) {
            return false;
        }
    }

    public void download(String url, Path target, Checks checks) throws IOException {
        if (matches(target, checks)) {
            return;
        }
        Path part = target.resolveSibling(target.getFileName() + PART);
        Files.createDirectories(target.toAbsolutePath().getParent());
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                fetch(url, part, checks.size());
                verify(part, checks);
                move(part, target);
                return;
            } catch (HostPolicy.BlockedHostException e) {
                Files.deleteIfExists(part);
                throw e;
            } catch (CorruptException e) {
                Files.deleteIfExists(part);
                last = e;
            } catch (IOException e) {
                last = e;
            }
            if (attempt < attempts) {
                backoff(attempt);
            }
        }
        Files.deleteIfExists(part);
        throw HostUnreachableException.classify(new IOException("Download failed: " + url, last), url);
    }

    /** Deletes {@code *.part} files older than {@code maxAge} under {@code root} (leftovers of killed runs). */
    public static void cleanStaleParts(Path root, Duration maxAge) {
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant limit = Instant.now().minus(maxAge);
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.getFileName().toString().endsWith(PART)).forEach(p -> {
                try {
                    FileTime time = Files.getLastModifiedTime(p);
                    if (time.toInstant().isBefore(limit)) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    private void fetch(String url, Path part, long expectedSize) throws IOException {
        long existing = Files.exists(part) ? Files.size(part) : 0L;
        if (expectedSize > 0 && existing > expectedSize) {
            Files.delete(part);
            existing = 0;
        }
        if (expectedSize > 0 && existing == expectedSize) {
            return;
        }
        HttpURLConnection conn = open(url, existing);
        try {
            int code = conn.getResponseCode();
            boolean resume = code == HttpURLConnection.HTTP_PARTIAL && existing > 0;
            if (resume) {
                String range = conn.getHeaderField("Content-Range");
                if (range == null || !range.startsWith("bytes " + existing + "-")) {
                    throw new CorruptException("Unexpected Content-Range: " + range);
                }
            } else if (code != HttpURLConnection.HTTP_OK) {
                if (code == 416) {
                    Files.deleteIfExists(part);
                }
                throw new IOException("HTTP " + code + " -> " + url);
            }
            StandardOpenOption mode = resume ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING;
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE, mode)) {
                in.transferTo(out);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void verify(Path file, Checks checks) throws IOException {
        long size = Files.size(file);
        if (checks.size() > 0 && size != checks.size()) {
            throw size < checks.size()
                    ? new IOException("Truncated download: " + file.getFileName() + " " + size + "/" + checks.size())
                    : new CorruptException("Size mismatch: " + file.getFileName());
        }
        if (checks.has(checks.sha1()) && !checks.sha1().equalsIgnoreCase(Hashes.sha1(file))) {
            throw new CorruptException("SHA-1 mismatch: " + file.getFileName());
        }
        if (checks.has(checks.sha256()) && !checks.sha256().equalsIgnoreCase(Hashes.sha256(file))) {
            throw new CorruptException("SHA-256 mismatch: " + file.getFileName());
        }
    }

    private static void move(Path part, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Opens {@code url}, following redirects by hand so that each hop is checked against the policy. */
    private HttpURLConnection open(String url, long rangeStart) throws IOException {
        String current = policy.upgradesHttp() ? HttpUtils.secure(url) : url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            policy.check(current);
            HttpURLConnection conn = (HttpURLConnection) ProxyConfig.current().open(new URL(current));
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (rangeStart > 0) {
                conn.setRequestProperty("Range", "bytes=" + rangeStart + "-");
            }
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) {
                    throw new IOException("Redirect without Location from " + current);
                }
                current = URI.create(current).resolve(location).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects: " + url);
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(4000L, backoffMs * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Data is wrong (hash or range mismatch): the partial file is discarded before the next attempt. */
    static final class CorruptException extends IOException {
        CorruptException(String message) {
            super(message);
        }
    }
}
