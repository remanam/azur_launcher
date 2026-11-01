package com.azur.launcher.net;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The ONE HTTP proxy used by every request of the launcher (downloads, pack sync, sign-in) and handed to the child
 * installer JVM. Host and port are persisted by the settings page; user and password live in memory only and are never
 * logged ({@link #toString} and {@link #describe} show host and port only).
 */
public final class ProxyConfig {

    public static final ProxyConfig NONE = new ProxyConfig("", 0, "", "");

    private static volatile ProxyConfig current = NONE;

    private final String host;
    private final int port;
    private final String user;
    private final String password;

    private ProxyConfig(String host, int port, String user, String password) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
    }

    /** Blank host or a port outside 1..65535 gives {@link #NONE}. */
    public static ProxyConfig of(String host, int port, String user, String password) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty() || port < 1 || port > 65535) {
            return NONE;
        }
        return new ProxyConfig(h, port, user == null ? "" : user.trim(), password == null ? "" : password);
    }

    /**
     * The proxy from HTTPS_PROXY / HTTP_PROXY (any case), or {@link #NONE}. Java ignores these variables itself, so
     * without this a launcher behind a proxy times out where curl works. A login inside the URL stays in memory only.
     */
    public static ProxyConfig fromEnvironment(java.util.Map<String, String> env) {
        for (String name : new String[] {"HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"}) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                URI uri = URI.create(value.contains("://") ? value.trim() : "http://" + value.trim());
                String info = uri.getUserInfo();
                int colon = info == null ? -1 : info.indexOf(':');
                String user = info == null ? "" : colon < 0 ? info : info.substring(0, colon);
                String pass = colon < 0 ? "" : info.substring(colon + 1);
                ProxyConfig found = of(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 80, user, pass);
                if (found.active()) {
                    return found;
                }
            } catch (IllegalArgumentException ignored) {
                // not a URL: try the next variable
            }
        }
        return NONE;
    }

    public static ProxyConfig current() {
        return current;
    }

    /** Replaces the proxy for all later requests and installs the proxy authenticator (idempotent). */
    public static void set(ProxyConfig config) {
        current = config == null ? NONE : config;
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        Authenticator.setDefault(new ProxyAuthenticator());
    }

    public boolean active() {
        return !host.isEmpty();
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean hasCredentials() {
        return !user.isEmpty();
    }

    /** Safe text for logs and the UI: {@code host:port} or {@code none}. */
    public String describe() {
        return active() ? host + ":" + port : "none";
    }

    @Override
    public String toString() {
        return "ProxyConfig[" + describe() + "]";
    }

    /** Opens a connection through the proxy when one is configured. */
    public URLConnection open(URL url) throws IOException {
        try {
            if (active() && !isLoopback(url.toURI())) {
                return url.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
            }
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Malformed URL", e);
        }
        return url.openConnection();
    }

    /** Selector for {@link java.net.http.HttpClient}: the proxy for every URI, the JVM default when none is set. */
    public static ProxySelector selector() {
        ProxySelector fallback = ProxySelector.getDefault();
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                ProxyConfig now = current;
                if (!now.active() || isLoopback(uri)) {
                    return fallback == null ? List.of(Proxy.NO_PROXY) : fallback.select(uri);
                }
                return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(now.host, now.port)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                // nothing to do: the caller reports the failure
            }
        };
    }

    /** Gives an {@link java.net.http.HttpClient} builder the proxy, and the proxy login when one is set. */
    public static java.net.http.HttpClient.Builder configure(java.net.http.HttpClient.Builder builder) {
        builder.proxy(selector());
        if (current.hasCredentials()) {
            builder.authenticator(new ProxyAuthenticator());
        }
        return builder;
    }

    /**
     * JVM flags that give a child process (the loader installer) the same proxy. Credentials are NOT passed: they
     * would show up in the process list; the launcher downloads the installer's libraries itself.
     */
    public List<String> jvmArgs() {
        List<String> args = new ArrayList<>();
        if (!active()) {
            args.add("-Djava.net.useSystemProxies=true");
            return args;
        }
        for (String scheme : new String[] {"https", "http"}) {
            args.add("-D" + scheme + ".proxyHost=" + host);
            args.add("-D" + scheme + ".proxyPort=" + port);
        }
        args.add("-Djdk.http.auth.tunneling.disabledSchemes=");
        return args;
    }

    private static boolean isLoopback(URI uri) {
        String h = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("[::1]") || h.equals("::1");
    }

    private static final class ProxyAuthenticator extends Authenticator {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            ProxyConfig now = current;
            if (getRequestorType() != RequestorType.PROXY || !now.hasCredentials()
                    || !now.host.equalsIgnoreCase(getRequestingHost())) {
                return null;
            }
            return new PasswordAuthentication(now.user, now.password.toCharArray());
        }
    }
}
