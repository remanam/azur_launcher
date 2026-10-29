package com.azur.launcher.auth;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;

/** Opens connections for auth traffic: https only, no redirects, optional proxy from {@link AuthSettings}. */
public final class AuthNetwork {

    private static final String USER_AGENT = "Azur-Launcher/1.0.0";

    private final AuthSettings settings;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final boolean allowPlainHttp;

    private AuthNetwork(AuthSettings settings, int connectTimeoutMs, int readTimeoutMs, boolean allowPlainHttp) {
        this.settings = settings;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.allowPlainHttp = allowPlainHttp;
    }

    /** Normal timeouts, used for the interactive sign-in. */
    public static AuthNetwork standard(AuthSettings settings) {
        return new AuthNetwork(settings, 15_000, 30_000, false);
    }

    /** Short timeouts for a launch where a still-valid cached session can be used if the hosts are unreachable. */
    public static AuthNetwork quick(AuthSettings settings) {
        return new AuthNetwork(settings, 5_000, 8_000, false);
    }

    /** Tests only: talks to a local http server. */
    static AuthNetwork forTests(int timeoutMs) {
        return new AuthNetwork(null, timeoutMs, timeoutMs, true);
    }

    HttpURLConnection open(String url, String method) throws IOException {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !allowPlainHttp) {
            throw new IOException("Plain http is not allowed for auth traffic");
        }
        URL target = uri.toURL();
        HttpURLConnection conn = (HttpURLConnection) (hasProxy()
                ? target.openConnection(new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress(settings.proxyHost(), settings.proxyPort())))
                : target.openConnection());
        conn.setRequestMethod(method);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private boolean hasProxy() {
        return settings != null && !settings.proxyHost().isEmpty() && settings.proxyPort() > 0;
    }
}
