package com.azur.launcher.net;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static entry point for game downloads. All traffic goes through one {@link Downloader} whose
 * {@link HostPolicy} is the central allowlist; {@link #allowHosts} adds our own hosts (mirror, server list).
 */
public final class HttpUtils {

    public static final String USER_AGENT = Downloader.USER_AGENT;

    /** Own https hosts allowed in addition to the official ones; the set only grows, it never shrinks. */
    private static final Set<String> ownHosts = ConcurrentHashMap.newKeySet();

    private static volatile String mirror = "";

    private static volatile Downloader downloader = Downloader.standard(HostPolicy.official());

    private HttpUtils() {
    }

    public static Downloader downloader() {
        return downloader;
    }

    /**
     * Allows the own mirror host (https) in addition to the official ones and to the hosts allowed before.
     * Blank keeps the current policy: a later call must never drop a host that was already needed.
     */
    public static void useMirror(String mirrorBase) {
        allowHosts(mirrorBase);
        if (mirrorBase != null && !mirrorBase.isBlank()) {
            mirror = mirrorBase.trim();
        }
    }

    /** The own mirror base set by {@link #useMirror}, or empty. */
    public static String mirror() {
        return mirror;
    }

    /** GET with the mirror first (hosts in {@link Mirrors}), then the official URL. */
    public static String getStringMirrored(String url) throws IOException {
        return downloader.getStringFirst(Mirrors.candidates(url, mirror));
    }

    /** Verified download with the mirror first, then the official URL. */
    public static void downloadMirrored(String url, Path target, Checks checks) throws IOException {
        downloader.downloadFirst(Mirrors.candidates(url, mirror), target, checks);
    }

    /**
     * Allows the hosts of the given https base URLs (own mirror, address of the signed server list) for every
     * later request. Blank, relative and plain-http values are ignored: a bad setting must not break the start,
     * and {@link HostPolicy} would reject such a URL anyway when it is actually used.
     */
    public static void allowHosts(String... baseUrls) {
        boolean changed = false;
        for (String base : baseUrls) {
            if (base == null || base.isBlank()) {
                continue;
            }
            try {
                URI uri = URI.create(base.trim());
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                    continue;
                }
                changed |= ownHosts.add(uri.getHost().toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // not a URL: reported by the download layer when the value is actually used
            }
        }
        if (changed) {
            downloader = Downloader.standard(HostPolicy.withHosts(Set.copyOf(ownHosts)));
        }
    }

    public static String getString(String url) throws IOException {
        return downloader.getString(url);
    }

    public static byte[] getBytes(String url) throws IOException {
        return downloader.getBytes(url);
    }

    /** Downloads {@code url} to {@code target}, resuming and verifying size and SHA-1 when given. */
    public static void download(String url, Path target, String expectedSha1, long expectedSize) throws IOException {
        downloader.download(url, target, Checks.of(expectedSha1, expectedSize));
    }

    /** Upgrades {@code http://} to {@code https://} (loopback excepted, see {@link HostPolicy}); the policy then decides. */
    public static String secure(String url) {
        if (url != null && url.regionMatches(true, 0, "http://", 0, 7) && !HostPolicy.isLoopbackHttp(url)) {
            return "https://" + url.substring(7);
        }
        return url;
    }
}
