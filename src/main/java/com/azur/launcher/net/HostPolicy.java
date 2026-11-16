package com.azur.launcher.net;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
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

    /**
     * Official hosts plus the given own hosts (already lower case, https only); empty means official only.
     * Used for the own mirror, the address of the signed server list and anything else we publish ourselves.
     */
    public static HostPolicy withHosts(Set<String> ownHosts) {
        if (Objects.isNull(ownHosts) || ownHosts.isEmpty()) {
            return official();
        }
        Set<String> all = new HashSet<>(OFFICIAL);
        all.addAll(ownHosts);
        return new HostPolicy(all, false);
    }

    /** Test policy: the given hosts over plain http on any port. Never used by the launcher itself. */
    public static HostPolicy forTests(Set<String> hosts) {
        return new HostPolicy(hosts, true);
    }

    /**
     * Local-test exception: plain http is accepted ONLY for the loopback literals 127.0.0.1,
     * [::1] and localhost, so a pack server on the developer's own machine can be used. No other http host passes.
     */
    public static boolean isLoopbackHttp(String url) {
        try {
            return isLoopbackHttp(URI.create(url));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isLoopbackHttp(URI uri) {
        String host = Objects.isNull(uri.getHost()) ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return "http".equalsIgnoreCase(uri.getScheme())
                && (host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("localhost"));
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
        String host = Objects.isNull(uri.getHost()) ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!looseForTests && isLoopbackHttp(uri)) {
            if (Objects.nonNull(uri.getRawUserInfo())) {
                throw new BlockedHostException("Credentials in URL: " + url);
            }
            return;
        }
        if (!looseForTests) {
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new BlockedHostException("Only https is allowed: " + url);
            }
            if (Objects.nonNull(uri.getRawUserInfo()) || (uri.getPort() != -1 && uri.getPort() != 443)) {
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
