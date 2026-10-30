package com.azur.launcher.net;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Own-mirror layout: {@code <mirror>/<official host>/<official path>} for the hosts that are often blocked
 * ({@code maven.neoforged.net}, {@code libraries.minecraft.net}). The mirror is tried first, the official URL is the
 * fallback. Integrity never depends on the mirror: callers pass the same hashes either way.
 */
public final class Mirrors {

    public static final Set<String> MIRRORED_HOSTS = Set.of("maven.neoforged.net", "libraries.minecraft.net");

    private Mirrors() {
    }

    /** URLs to try in order: the mirror copy (when the host is mirrored and a base is set), then {@code url}. */
    public static List<String> candidates(String url, String mirrorBase) {
        List<String> list = new ArrayList<>(2);
        String mirrored = mirrorUrl(url, mirrorBase);
        if (mirrored != null) {
            list.add(mirrored);
        }
        list.add(url);
        return list;
    }

    /** The mirror URL of an official URL, or null when there is no base or the host is not mirrored. */
    public static String mirrorUrl(String url, String mirrorBase) {
        if (mirrorBase == null || mirrorBase.isBlank() || url == null) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!MIRRORED_HOSTS.contains(host)) {
                return null;
            }
            String base = mirrorBase.trim();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return base + "/" + host + uri.getRawPath();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
