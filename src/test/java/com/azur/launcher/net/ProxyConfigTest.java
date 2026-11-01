package com.azur.launcher.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Authenticator;
import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProxyConfigTest {

    @AfterEach
    void reset() {
        ProxyConfig.set(ProxyConfig.NONE);
    }

    @Test
    void blankHostOrBadPortMeansNone() {
        assertSame(ProxyConfig.NONE, ProxyConfig.of("", 8080, "", ""));
        assertSame(ProxyConfig.NONE, ProxyConfig.of("proxy.test", 0, "", ""));
        assertSame(ProxyConfig.NONE, ProxyConfig.of("proxy.test", 70000, "", ""));
        assertTrue(ProxyConfig.of("proxy.test", 8080, "", "").active());
    }

    @Test
    void selectorReturnsTheProxyForRemoteHostsButNotLoopback() {
        ProxyConfig.set(ProxyConfig.of("proxy.test", 3128, "", ""));
        List<Proxy> remote = ProxyConfig.selector().select(URI.create("https://maven.neoforged.net/x"));
        assertEquals(Proxy.Type.HTTP, remote.get(0).type());
        assertTrue(remote.get(0).address().toString().contains("proxy.test"));
        List<Proxy> local = ProxyConfig.selector().select(URI.create("http://127.0.0.1:8781/x"));
        assertEquals(Proxy.NO_PROXY, local.get(0));
    }

    @Test
    void noProxyFallsBackToTheJvmDefault() {
        ProxyConfig.set(ProxyConfig.NONE);
        assertFalse(ProxyConfig.selector().select(URI.create("https://example.org/")).isEmpty());
    }

    @Test
    void authenticatorAnswersOnlyTheProxyItself() throws Exception {
        ProxyConfig.set(ProxyConfig.of("proxy.test", 3128, "alice", "secret"));
        Authenticator auth = authenticatorOf(java.net.http.HttpClient.newBuilder());
        PasswordAuthentication proxy = Authenticator.requestPasswordAuthentication(auth, "proxy.test",
                InetAddress.getLoopbackAddress(), 3128, "https", "", "basic", new URL("https://x.test/"),
                Authenticator.RequestorType.PROXY);
        assertEquals("alice", proxy.getUserName());
        PasswordAuthentication server = Authenticator.requestPasswordAuthentication(auth, "proxy.test",
                InetAddress.getLoopbackAddress(), 443, "https", "", "basic", new URL("https://x.test/"),
                Authenticator.RequestorType.SERVER);
        assertNull(server);
        PasswordAuthentication other = Authenticator.requestPasswordAuthentication(auth, "other.test",
                InetAddress.getLoopbackAddress(), 3128, "https", "", "basic", new URL("https://x.test/"),
                Authenticator.RequestorType.PROXY);
        assertNull(other);
    }

    private static Authenticator authenticatorOf(java.net.http.HttpClient.Builder builder) {
        return ProxyConfig.configure(builder).build().authenticator().orElseThrow();
    }

    @Test
    void installerJvmArgsCarryHostPortAndEnableTunnelAuthButNeverTheCredentials() {
        List<String> args = ProxyConfig.of("proxy.test", 3128, "alice", "secret").jvmArgs();
        assertTrue(args.contains("-Dhttps.proxyHost=proxy.test"));
        assertTrue(args.contains("-Dhttps.proxyPort=3128"));
        assertTrue(args.contains("-Dhttp.proxyHost=proxy.test"));
        assertTrue(args.contains("-Djdk.http.auth.tunneling.disabledSchemes="));
        assertFalse(String.join(" ", args).contains("secret"));
        assertFalse(String.join(" ", args).contains("alice"));
        assertEquals(List.of("-Djava.net.useSystemProxies=true"), ProxyConfig.NONE.jvmArgs());
    }

    @Test
    void textFormsNeverShowCredentials() {
        ProxyConfig config = ProxyConfig.of("proxy.test", 3128, "alice", "secret");
        assertEquals("proxy.test:3128", config.describe());
        assertFalse(config.toString().contains("secret"));
        assertFalse(config.toString().contains("alice"));
    }

    @org.junit.jupiter.api.Test
    void environmentProxyIsReadAndNeverDescribedWithLogin() {
        ProxyConfig p = ProxyConfig.fromEnvironment(java.util.Map.of("HTTPS_PROXY", "http://u:secret@proxy.example:3128"));
        org.junit.jupiter.api.Assertions.assertEquals("proxy.example:3128", p.describe());
        org.junit.jupiter.api.Assertions.assertTrue(p.hasCredentials());
        org.junit.jupiter.api.Assertions.assertFalse(p.toString().contains("secret"));
        org.junit.jupiter.api.Assertions.assertFalse(ProxyConfig.fromEnvironment(java.util.Map.of()).active());
    }
}
