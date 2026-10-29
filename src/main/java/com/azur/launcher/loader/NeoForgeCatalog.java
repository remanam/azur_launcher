package com.azur.launcher.loader;

import com.azur.launcher.net.HttpUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lists NeoForge builds and builds installer URLs from the official NeoForged Maven. */
public final class NeoForgeCatalog {

    public static final String METADATA_URL =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    public static final String MAVEN_URL = "https://maven.neoforged.net/releases/net/neoforged/neoforge";

    private static final Pattern XML_VERSION = Pattern.compile("<version>([^<]+)</version>");
    private static final int LIMIT = 200;
    private static final long FETCH_TIMEOUT_SECONDS = 20;

    private NeoForgeCatalog() {
    }

    /** NeoForge builds for a Minecraft version, newest first. */
    public static List<String> versions(String mcVersion) throws IOException {
        String prefix = versionPrefix(mcVersion);
        if (prefix == null) {
            throw new IOException("NeoForge does not support Minecraft " + mcVersion);
        }
        return filter(HttpUtils.getString(METADATA_URL), prefix);
    }

    /** Every NeoForge build, fetched with a timeout so a blocked network fails visibly instead of hanging. */
    public static List<String> allVersions() throws IOException {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "azur-neoforge-list");
            t.setDaemon(true);
            return t;
        });
        try {
            String xml = pool.submit(() -> HttpUtils.getString(METADATA_URL))
                    .get(FETCH_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            return parseAll(xml);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("timeout after " + FETCH_TIMEOUT_SECONDS + " s", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException(e.getCause() == null ? e.getMessage() : e.getCause().getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Pure part of {@link #allVersions}: all {@code <version>} values of the maven-metadata XML. */
    static List<String> parseAll(String metadataXml) {
        Matcher matcher = XML_VERSION.matcher(metadataXml);
        List<String> all = new ArrayList<>();
        while (matcher.find()) {
            all.add(matcher.group(1).trim());
        }
        return all;
    }

    /** Pure part of {@link #versions}: picks builds with the prefix from the maven-metadata XML. */
    static List<String> filter(String metadataXml, String prefix) throws IOException {
        Matcher matcher = XML_VERSION.matcher(metadataXml);
        List<String> matched = new ArrayList<>();
        while (matcher.find()) {
            String version = matcher.group(1).trim();
            if (version.startsWith(prefix + ".")) {
                matched.add(version);
            }
        }
        if (matched.isEmpty()) {
            throw new IOException("No NeoForge builds for prefix " + prefix);
        }
        matched.sort(Comparator.comparing((String v) -> v, NeoForgeCatalog::compareVersion).reversed());
        return matched.size() > LIMIT ? matched.subList(0, LIMIT) : matched;
    }

    public static String installerUrl(String neoForgeVersion) {
        return MAVEN_URL + "/" + neoForgeVersion + "/neoforge-" + neoForgeVersion + "-installer.jar";
    }

    /** {@code 1.21.1 -> 21.1}, {@code 1.21 -> 21.0}; null before 1.20.2 (NeoForge starts there). */
    public static String versionPrefix(String mcVersion) {
        if (mcVersion == null || !mcVersion.startsWith("1.")) {
            return null;
        }
        String[] parts = mcVersion.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        int minor = parseIntSafe(parts[1]);
        int patch = parts.length >= 3 ? parseIntSafe(parts[2]) : 0;
        if (minor < 20 || (minor == 20 && patch < 2)) {
            return null;
        }
        return minor + "." + patch;
    }

    /** Segment-wise comparison; a release ranks above its {@code -beta} pre-release. */
    static int compareVersion(String left, String right) {
        String[] a = left.split("[.\\-+]");
        String[] b = right.split("[.\\-+]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "";
            String y = i < b.length ? b[i] : "";
            if (x.isEmpty() != y.isEmpty()) {
                String rest = x.isEmpty() ? y : x;
                if (!rest.matches("\\d+")) {
                    return x.isEmpty() ? 1 : -1;
                }
            }
            int cmp = x.matches("\\d+") && y.matches("\\d+")
                    ? Integer.compare(parseIntSafe(x), parseIntSafe(y))
                    : x.compareTo(y);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static int parseIntSafe(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
