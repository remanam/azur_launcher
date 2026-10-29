package com.azur.launcher.net;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Central download allowlist: https only, official hosts (those the Mojang manifests and the NeoForged Maven use)
 * plus an optional own mirror. Applied to the first URL and to every redirect hop.
 */
public final class HostPolicy {

    /** Hosts used by the Mojang version manifest, version JSONs, libraries, assets and Java runtime manifests. */
    public static final Set<String> OFFICIAL = Set.of(
            "piston-meta.mojang.com",
            "piston-data.mojang.com",
            "launchermeta.mojang.com",
            "launcher.mojang.com",
            "libraries.minecraft.net",
            "resources.download.minecraft.net",
            "maven.neoforged.net");

    private final Set<String> hosts;
    private final boolean looseForTests;

    private HostPolicy(Set<String> hosts, boolean looseForTests) {
        this.hosts = Set.copyOf(hosts);
        this.looseForTests = looseForTests;
    }

    public static HostPolicy official() {
        return new HostPolicy(OFFICIAL, false);
    }

    /** Official hosts plus the host of {@code mirrorBase} (must be https); blank means no mirror. */
    public static HostPolicy withMirror(String mirrorBase) {
        if (mirrorBase == null || mirrorBase.isBlank()) {
            return official();
        }
        URI uri = URI.create(mirrorBase.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Mirror base URL must be https: " + mirrorBase);
        }
        Set<String> all = new HashSet<>(OFFICIAL);
        all.add(uri.getHost().toLowerCase(Locale.ROOT));
        return new HostPolicy(all, false);
    }

    /** Test policy: the given hosts over plain http on any port. Never used by the launcher itself. */
    public static HostPolicy forTests(Set<String> hosts) {
        return new HostPolicy(hosts, true);
    }

    /** Production policies upgrade {@code http://} to {@code https://} before checking. */
    boolean upgradesHttp() {
        return !looseForTests;
    }

    public boolean allows(String url) {
        try {
            check(url);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** @throws BlockedHostException when the URL is not https or its host is not on the list */
    public void check(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new BlockedHostException("Malformed URL: " + url);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!looseForTests) {
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new BlockedHostException("Only https is allowed: " + url);
            }
            if (uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new BlockedHostException("Credentials or custom port in URL: " + url);
            }
        }
        if (!hosts.contains(host)) {
            throw new BlockedHostException("Host is not allowed: " + host);
        }
    }

    /** Not retried: a blocked host never becomes allowed. */
    public static final class BlockedHostException extends IOException {
        public BlockedHostException(String message) {
            super(message);
        }
    }
}
