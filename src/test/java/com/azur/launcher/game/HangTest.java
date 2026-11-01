package com.azur.launcher.game;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class HangTest {

    private static final List<String> DUMP = List.of(
            "noise before",
            "2026-11-01 23:00:00",
            "Full thread dump OpenJDK 64-Bit Server VM (21 mixed mode):",
            "",
            "\"Render thread\" #20 prio=5 tid=0x1 nid=0x2 waiting on condition",
            "   java.lang.Thread.State: WAITING (parking)",
            "\tat jdk.internal.misc.Unsafe.park(Native Method)",
            "",
            "\"modloading-worker-0\" #40 prio=5 tid=0x3 nid=0x4 runnable",
            "   java.lang.Thread.State: RUNNABLE",
            "\tat sun.nio.ch.Net.connect0(Native Method)",
            "\tat sun.nio.ch.Net.connect(Net.java:589)",
            "\tat java.net.http.HttpClient.send(HttpClient.java:1)",
            "\tat org.zeith.hammerlib.core.adapter.ModSourceAdapter.<clinit>(ModSourceAdapter.java:30)",
            "\tat org.zeith.hammerlib.core.CommonMessages.printMessageOnIllegalRedistribution(CommonMessages.java:1)",
            "\tat org.zeith.hammerlib.HammerLib.<init>(HammerLib.java:1)",
            "");

    @Test
    void hammerLibNetworkHang() {
        HangDiagnosis d = HangDiagnosis.parse(HangDiagnosis.lastDump(DUMP));
        assertNotNull(d);
        assertEquals("modloading-worker-0", d.thread());
        assertEquals("hammerlib", d.mod());
        assertTrue(d.waitingNetwork());
        assertTrue(d.message("ru").contains("hammerlib (ждёт ответа сети)"));
        assertTrue(d.message("en").contains("hammerlib (waiting for a network reply)"));
    }

    @Test
    void noDumpGivesNothing() {
        assertTrue(HangDiagnosis.lastDump(List.of("a", "b")).isEmpty());
        assertNull(HangDiagnosis.parse(List.of()));
    }

    @Test
    void watchdogThresholds() {
        LaunchWatchdog w = new LaunchWatchdog(120, 480, 0);
        w.observeSize(10, 1000);
        assertFalse(w.hung(100_000));
        assertTrue(w.hung(121_000), "no growth for 120 s");
        w.observeSize(11, 121_000);
        assertFalse(w.hung(130_000), "growth resets the clock");
        w.observeSize(11, 130_000);
        assertTrue(w.hung(480_000), "startup limit");
    }

    @Test
    void readyAndDisabled() {
        LaunchWatchdog w = new LaunchWatchdog(120, 480, 0);
        assertTrue(LaunchWatchdog.isReadyLine("[..] Sound engine started"));
        w.markReady();
        assertFalse(w.hung(10_000_000));
        LaunchWatchdog off = new LaunchWatchdog(0, 0, 0);
        assertFalse(off.hung(10_000_000));
        LaunchWatchdog post = new LaunchWatchdog(120, 0, 0);
        post.postpone(120_000, 130_000);
        assertFalse(post.hung(200_000));
        assertTrue(post.hung(260_000));
    }
}
