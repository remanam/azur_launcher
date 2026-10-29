package com.azur.launcher.net;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Static entry point for game downloads. All traffic goes through one {@link Downloader} whose
 * {@link HostPolicy} is the central allowlist; {@link #useMirror} adds the own mirror host.
 */
public final class HttpUtils {

    public static final String USER_AGENT = Downloader.USER_AGENT;

    private static volatile Downloader downloader = Downloader.standard(HostPolicy.official());

    private HttpUtils() {
    }

    public static Downloader downloader() {
        return downloader;
    }

    /** Allows the own mirror host (https) in addition to the official ones; blank resets to official only. */
    public static void useMirror(String mirrorBase) {
        downloader = Downloader.standard(HostPolicy.withMirror(mirrorBase));
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

    /** Upgrades {@code http://} to {@code https://}; the policy then decides about the host. */
    public static String secure(String url) {
        if (url != null && url.regionMatches(true, 0, "http://", 0, 7)) {
            return "https://" + url.substring(7);
        }
        return url;
    }

    public static String sha1(Path file) throws IOException {
        return Hashes.sha1(file);
    }

    public static String toHex(byte[] bytes) {
        return Hashes.toHex(bytes);
    }
}
