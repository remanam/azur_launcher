package com.azur.launcher.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Build-time branding of a distribution. A fork may ship an optional classpath file {@code /branding.properties}
 * (untracked, see docs/BRANDING.md); without it the generic defaults below apply: no server list, no site link.
 */
public final class Branding {

    private static final Properties PROPS = load();

    private Branding() {
    }

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = Branding.class.getResourceAsStream("/branding.properties")) {
            if (in != null) {
                p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // generic defaults
        }
        return p;
    }

    /** Value of {@code key}, or {@code fallback} when absent or blank. */
    public static String get(String key, String fallback) {
        String v = PROPS.getProperty(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /** Release address of the signed servers.json; empty: only the cache and the user setting. */
    public static String serversUrl() {
        return get("servers_url", "");
    }

    /** Site opened from the update chip; empty: no site. */
    public static String siteUrl() {
        return get("site_url", "");
    }

    /** Privacy page used when the servers URL has no host. */
    public static String privacyUrl() {
        return get("privacy_url", "");
    }
}
