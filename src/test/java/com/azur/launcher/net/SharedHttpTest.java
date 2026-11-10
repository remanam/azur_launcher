package com.azur.launcher.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class SharedHttpTest {

    @Test
    void oneClientForAllCallers() {
        HttpClient a = SharedHttp.client();
        int built = SharedHttp.built();
        for (int i = 0; i < 20; i++) {
            assertSame(a, SharedHttp.client());
        }
        assertEquals(built, SharedHttp.built());
    }

    @Test
    void proxyChangeRebuildsOnce() {
        HttpClient a = SharedHttp.client();
        ProxyConfig keep = ProxyConfig.current();
        ProxyConfig.set(keep);
        HttpClient b = SharedHttp.client();
        assertNotSame(a, b);
        assertSame(b, SharedHttp.client());
    }
}
