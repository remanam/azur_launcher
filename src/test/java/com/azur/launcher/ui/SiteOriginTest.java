package com.azur.launcher.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SiteOriginTest {
    @Test
    void siteLivesNextToTheServerList() {
        assertEquals("https://example.test", AppContext.siteOrigin("https://example.test/servers.json"));
        assertEquals("http://127.0.0.1:8790", AppContext.siteOrigin("http://127.0.0.1:8790/servers.json"));
        assertEquals("", AppContext.siteOrigin(""));
        assertEquals("", AppContext.siteOrigin("not a url"));
    }
}
