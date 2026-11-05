package com.azur.launcher.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import com.azur.launcher.account.SiteException;
import com.azur.launcher.config.LauncherConfig;
import com.azur.launcher.pack.PackException;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TelemetryTest {

    /** Same rules as site/server/analytics_launcher.go (telInstallRe, telVersionRe, enums). */
    private static final Pattern SITE_BODY = Pattern.compile(
            "\\{\"install_id\":\"[0-9a-f]{32}\",\"v\":\"[0-9]{1,3}(\\.[0-9]{1,3}){1,3}\",\"os\":\"(windows|macos|linux|other)\","
                    + "\"step\":\"(start|java|login|manifest|download|verify|install|launch|update)\",\"ok\":(true|false),"
                    + "\"code\":\"(|network|signature|java|mod|disk|auth|other)\",\"ms\":[0-9]{1,7}\\}");
    private static final String ID = "0123456789abcdef0123456789abcdef";

    @Test
    void offByDefaultAndSendsNothing() throws Exception {
        LauncherConfig config = new LauncherConfig();
        assertFalse(config.isTelemetryEnabled());
        assertEquals("", config.getTelemetryId());
        List<String> sent = new CopyOnWriteArrayList<>();
        TelemetryClient c = new TelemetryClient(config::isTelemetryEnabled, config::getTelemetryId, "1.0.6", "macos", (p, j) -> sent.add(j));
        c.report("install", true, "", 5);
        Thread.sleep(100);
        assertTrue(sent.isEmpty());
        assertEquals(0, c.queued());
    }

    @Test
    void sentBodyMatchesTheSiteContractAndCarriesNoPersonalData() throws Exception {
        LauncherConfig config = new LauncherConfig();
        config.setTelemetryEnabled(true);
        assertEquals(32, config.getTelemetryId().length());
        List<String> sent = new CopyOnWriteArrayList<>();
        CountDownLatch two = new CountDownLatch(2);
        TelemetryClient c = new TelemetryClient(config::isTelemetryEnabled, config::getTelemetryId, "1.0.6", "windows", (p, j) -> {
            assertEquals("/api/telemetry/launcher", p);
            sent.add(j);
            two.countDown();
        });
        c.report("install", true, "", 1234);
        c.report("download", false, ErrorCode.NETWORK, 99);
        assertTrue(two.await(3, TimeUnit.SECONDS));
        for (String body : sent) {
            assertTrue(SITE_BODY.matcher(body).matches(), body);
            assertFalse(body.contains("/") || body.contains("\\"), "no paths");
        }
    }

    @Test
    void failuresAndFullQueueNeverBlockOrThrow() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        TelemetryClient c = new TelemetryClient(() -> true, () -> ID, "1.0.6", "linux", (p, j) -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("down");
        });
        long t0 = System.nanoTime();
        for (int i = 0; i < 500; i++) {
            c.report("start", true, "", 0);
        }
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500, "report must not wait for the network");
        assertTrue(c.queued() <= 32, "queue is bounded");
        release.countDown();
    }

    @Test
    void switchingOffForgetsIdAndAsksSiteToErase() throws Exception {
        LauncherConfig config = new LauncherConfig();
        config.setTelemetryEnabled(true);
        String id = config.getTelemetryId();
        assertEquals(id, config.getTelemetryId(), "id is stable while on");
        String old = config.setTelemetryEnabled(false);
        assertEquals(id, old);
        assertEquals("", config.getTelemetryId());
        CountDownLatch got = new CountDownLatch(1);
        List<String> paths = new CopyOnWriteArrayList<>();
        new TelemetryClient(() -> false, () -> "", "1.0.6", "other", (p, j) -> {
            paths.add(p + " " + j);
            got.countDown();
        }).forget(old);
        assertTrue(got.await(3, TimeUnit.SECONDS));
        assertEquals("/api/telemetry/launcher/forget {\"install_id\":\"" + id + "\"}", paths.get(0));
    }

    @Test
    void errorWordsComeFromTheFixedList() {
        assertEquals("network", ErrorCode.classify(new PackException(PackException.NETWORK, "x")));
        assertEquals("signature", ErrorCode.classify(new PackException(PackException.SIGNATURE, "x")));
        assertEquals("mod", ErrorCode.classify(new PackException(PackException.APPLY, "x")));
        assertEquals("network", ErrorCode.classify(new IOException("wrapped", new UnknownHostException("h"))));
        assertEquals("disk", ErrorCode.classify(new IOException("No space left on device")));
        assertEquals("signature", ErrorCode.classify(new IOException("SHA-1 mismatch for /home/u/file")));
        assertEquals("java", ErrorCode.classify(new IllegalStateException("Java runtime not found")));
        assertEquals("auth", ErrorCode.classify(new IOException("x", new SiteException("no answer", null))));
        assertEquals("other", ErrorCode.classify(new RuntimeException("/Users/steve/secret path")));
        assertEquals("other", new TelemetryEvent("start", false, null, 0).code());
        assertEquals("", new TelemetryEvent("start", true, "network", 0).code());
        assertEquals(3_600_000L, new TelemetryEvent("start", true, "", Long.MAX_VALUE).ms());
    }

    @Test
    void osNames() {
        assertEquals("windows", TelemetryClient.osName("Windows 11"));
        assertEquals("macos", TelemetryClient.osName("Mac OS X"));
        assertEquals("linux", TelemetryClient.osName("Linux"));
        assertEquals("other", TelemetryClient.osName("FreeBSD"));
    }
}
