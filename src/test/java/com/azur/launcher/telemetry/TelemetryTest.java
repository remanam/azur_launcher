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

    /** Same rules as the site analytics validator (telInstallRe, telVersionRe, enums). */
    private static final Pattern SITE_BODY = Pattern.compile(
            "\\{\"install_id\":\"[0-9a-f]{32}\",\"v\":\"[0-9]{1,3}(\\.[0-9]{1,3}){1,3}\",\"os\":\"(windows|macos|linux|other)\","
                    + "\"step\":\"(start|java|login|manifest|download|verify|install|launch|update)\",\"ok\":(true|false),"
                    + "\"code\":\"(|network|signature|java|mod|disk|auth|other)\",\"ms\":[0-9]{1,7}\\}");
    private static final String ID = "0123456789abcdef0123456789abcdef";

    @Test
    void onByDefaultWithRandomIdAndSwitchOffStopsSending() throws Exception {
        LauncherConfig config = new LauncherConfig();
        assertTrue(config.isTelemetryEnabled());
        assertTrue(config.getTelemetryId().matches("[0-9a-f]{32}"));
        List<String> sent = new CopyOnWriteArrayList<>();
        CountDownLatch one = new CountDownLatch(1);
        TelemetryClient c = new TelemetryClient(config::isTelemetryEnabled, config::getTelemetryId, "1.0.6", "macos", (p, j) -> {
            sent.add(j);
            one.countDown();
        });
        c.report("install", true, "", 5);
        assertTrue(one.await(3, TimeUnit.SECONDS));
        config.setTelemetryEnabled(false);
        assertEquals("", config.getTelemetryId());
        c.report("install", true, "", 5);
        Thread.sleep(100);
        assertEquals(1, sent.size());
        assertEquals(0, c.queued());
    }

    @Test
    void noticeAndPrivacyTextsExistInBothLanguages() throws Exception {
        for (String lang : new String[] {"ru", "en"}) {
            java.util.Properties p = new java.util.Properties();
            try (var in = TelemetryTest.class.getResourceAsStream("/messages_" + lang + ".properties");
                    var r = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                p.load(r);
            }
            for (String k : new String[] {"telemetry.notice", "telemetry.privacy", "telemetry.forget", "telemetry.title"}) {
                assertFalse(p.getProperty(k, "").isBlank(), lang + " " + k);
            }
        }
    }

    @Test
    void sentBodyMatchesTheSiteContractAndCarriesNoPersonalData() throws Exception {
        LauncherConfig config = new LauncherConfig();
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
            assertFalse(body.matches("(?is).*(nick|name|path|ip|log|user).*"), "no forbidden fields");
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

    @Test
    void nothingIsSentOrQueuedBeforeTheNoticeIsShownThenItIs() throws Exception {
        LauncherConfig config = new LauncherConfig();
        assertTrue(config.migrateTelemetryNotice(false) == false);
        List<String> sent = new CopyOnWriteArrayList<>();
        CountDownLatch one = new CountDownLatch(1);
        TelemetryClient c = new TelemetryClient(config::isTelemetrySendable, config::getTelemetryId, "1.0.6", "linux", (p, j) -> {
            sent.add(j);
            one.countDown();
        });
        c.report("start", true, "", 0);
        Thread.sleep(100);
        assertEquals(0, sent.size());
        assertEquals(0, c.queued());
        assertTrue(config.markTelemetryNoticeShown());
        assertFalse(config.markTelemetryNoticeShown());
        c.report("install", true, "", 1);
        assertTrue(one.await(3, TimeUnit.SECONDS));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("\"step\":\"install\""));
    }

    @Test
    void finishedWizardCountsAsNoticeShownAndSwitchStillWorks() throws Exception {
        LauncherConfig old = new LauncherConfig();
        assertTrue(old.migrateTelemetryNotice(true));
        assertTrue(old.isTelemetrySendable());
        assertFalse(old.migrateTelemetryNotice(false), "decided once");
        List<String> sent = new CopyOnWriteArrayList<>();
        List<String> forgotten = new CopyOnWriteArrayList<>();
        CountDownLatch forget = new CountDownLatch(1);
        TelemetryClient c = new TelemetryClient(old::isTelemetrySendable, old::getTelemetryId, "1.0.6", "linux", (p, j) -> {
            if (p.equals(TelemetryClient.FORGET_PATH)) {
                forgotten.add(j);
                forget.countDown();
            } else {
                sent.add(j);
            }
        });
        String id = old.getTelemetryId();
        c.forget(old.setTelemetryEnabled(false));
        assertTrue(forget.await(3, TimeUnit.SECONDS));
        assertTrue(forgotten.get(0).contains(id));
        assertFalse(old.isTelemetrySendable());
        c.report("install", true, "", 1);
        Thread.sleep(100);
        assertEquals(0, sent.size());
    }
}
