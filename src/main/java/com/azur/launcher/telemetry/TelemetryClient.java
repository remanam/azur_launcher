package com.azur.launcher.telemetry;

import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Anonymous telemetry (on by default, but only after the notice was shown; the {@code enabled} supplier covers both). {@link #report} never blocks and never throws: it drops the event when telemetry is off,
 * when the queue (32) is full or when the site does not answer. One daemon thread sends, one attempt per event. The only
 * identifier is the random install id; see the privacy page of the configured site.
 */
public final class TelemetryClient {

    static final String PATH = "/api/telemetry/launcher";
    static final String FORGET_PATH = "/api/telemetry/launcher/forget";
    private static final int QUEUE = 32;

    private final BooleanSupplier enabled;
    private final Supplier<String> installId;
    private final String version;
    private final String os;
    private final TelemetrySender sender;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE);
    private Thread worker;

    public TelemetryClient(BooleanSupplier enabled, Supplier<String> installId, String version, TelemetrySender sender) {
        this(enabled, installId, version, osName(System.getProperty("os.name", "")), sender);
    }

    TelemetryClient(BooleanSupplier enabled, Supplier<String> installId, String version, String os, TelemetrySender sender) {
        this.enabled = enabled;
        this.installId = installId;
        this.version = version;
        this.os = os;
        this.sender = sender;
    }

    public void report(String step, boolean ok, String code, long ms) {
        report(new TelemetryEvent(step, ok, code, ms));
    }

    public void report(TelemetryEvent event) {
        if (!enabled.getAsBoolean()) {
            return; // off, or the notice has not been shown yet: nothing is queued, no id is created
        }
        String id = installId.get();
        if (id == null || id.isEmpty()) {
            return;
        }
        if (!queue.offer(json(id, event))) {
            return; // full: the newest event is dropped, the launcher is never slowed down
        }
        startWorker();
    }

    /** The user switched telemetry off: ask the site to erase every row of this install id (best effort, in the background). */
    public void forget(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                sender.post(FORGET_PATH, "{\"install_id\":\"" + id + "\"}");
            } catch (Exception ignored) {
                // best effort
            }
        }, "azur-telemetry-forget");
        t.setDaemon(true);
        t.start();
    }

    int queued() {
        return queue.size();
    }

    String json(String id, TelemetryEvent e) {
        return "{\"install_id\":\"" + id + "\",\"v\":\"" + version + "\",\"os\":\"" + os + "\",\"step\":\"" + e.step()
                + "\",\"ok\":" + e.ok() + ",\"code\":\"" + e.code() + "\",\"ms\":" + e.ms() + "}";
    }

    private synchronized void startWorker() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        worker = new Thread(this::drain, "azur-telemetry");
        worker.setDaemon(true);
        worker.start();
    }

    private void drain() {
        String body;
        while ((body = queue.poll()) != null) {
            try {
                sender.post(PATH, body);
            } catch (Exception ignored) {
                // never retried, never logged with content
            }
        }
    }

    static String osName(String property) {
        String n = property.toLowerCase(Locale.ROOT);
        if (n.contains("win")) {
            return "windows";
        }
        if (n.contains("mac") || n.contains("darwin")) {
            return "macos";
        }
        return n.contains("linux") ? "linux" : "other";
    }
}
