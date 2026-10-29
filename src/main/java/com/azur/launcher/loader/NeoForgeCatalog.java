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
