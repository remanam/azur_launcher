package com.azur.launcher.ui;

import com.azur.launcher.i18n.I18n;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Pure progress model of one install or update: current step, fraction, bytes, speed (smoothed over about 2 s) and
 * ETA. Fed from worker threads, read by the UI timer through {@link #snapshot()}. A step without byte counts is
 * shown by file count, a step without any count is indeterminate: no made-up percent.
 */
final class InstallProgress {

    /** Steps in the order the player sees them; {@link #key()} is the i18n key of the name. */
    enum Step {
        CHECK("progress.step.check"), PACK("progress.step.pack"), MINECRAFT("progress.step.minecraft"),
        NEOFORGE("progress.step.neoforge"), LIBRARIES("progress.step.libraries"), DONE("progress.step.done");

        private final String key;

        Step(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    /** What the view draws; {@code fraction} is -1 when unknown (indeterminate bar). */
    record Snapshot(Step step, double fraction, String detail) {
    }

    static final long WINDOW_NANOS = 2_000_000_000L;
    private static final double MB = 1024.0 * 1024.0;

    private final LongSupplier clock;
    private final ArrayDeque<long[]> samples = new ArrayDeque<>();
    private Step step = Step.CHECK;
    private boolean bytes;
    private long done;
    private long total;
    private boolean fresh;

    InstallProgress(LongSupplier nanoClock) {
        this.clock = nanoClock;
    }

    InstallProgress() {
        this(System::nanoTime);
    }

    /** Starts a run; {@code freshInstall}: nothing was installed before (wording «Устанавливаем» vs «Обновляем»). */
    synchronized void begin(boolean freshInstall) {
        fresh = freshInstall;
        reset(Step.CHECK);
    }

    synchronized boolean fresh() {
        return fresh;
    }

    synchronized Step step() {
        return step;
    }

    /** Enters a step with no counts yet (indeterminate until {@link #bytes} or {@link #files} arrives). */
    synchronized void step(Step next) {
        if (next != step) {
            reset(next);
        }
    }

    /** Byte progress of the current step. */
    synchronized void bytes(Step at, long doneBytes, long totalBytes) {
        update(at, true, doneBytes, totalBytes);
    }

    /** File-count progress of the current step. */
    synchronized void files(Step at, long doneFiles, long totalFiles) {
        update(at, false, doneFiles, totalFiles);
    }

    synchronized void finish() {
        reset(Step.DONE);
        done = 1;
        total = 1;
    }

    private void update(Step at, boolean asBytes, long d, long t) {
        if (at != step || bytes != asBytes) {
            reset(at);
            bytes = asBytes;
        }
        done = d;
        total = t;
        if (asBytes) {
            long now = clock.getAsLong();
            samples.addLast(new long[] {now, d});
            while (samples.size() > 2 && now - samples.peekFirst()[0] > WINDOW_NANOS) {
                samples.removeFirst();
            }
        }
    }

    private void reset(Step next) {
        step = next;
        bytes = false;
        done = 0;
        total = 0;
        samples.clear();
    }

    /** Bytes per second over the last ~2 s; 0 until two samples exist. */
    synchronized double speed() {
        if (samples.size() < 2) {
            return 0;
        }
        long[] first = samples.peekFirst();
        long[] last = samples.peekLast();
        long dt = last[0] - first[0];
        return dt <= 0 ? 0 : (last[1] - first[1]) * 1e9 / dt;
    }

    /** Seconds left at the current speed, or -1 when unknown. */
    synchronized long etaSeconds() {
        double speed = speed();
        if (!bytes || total <= 0 || speed < 1) {
            return -1;
        }
        return Math.max(0, Math.round((total - done) / speed));
    }

    synchronized Snapshot snapshot() {
        if (step == Step.DONE) {
            return new Snapshot(step, 1.0, "");
        }
        if (total <= 0) {
            return new Snapshot(step, -1, "");
        }
        double fraction = Math.max(0, Math.min(1.0, (double) done / total));
        int percent = (int) Math.round(fraction * 100);
        if (!bytes) {
            return new Snapshot(step, fraction, I18n.tr("progress.files", percent, done, total));
        }
        long eta = etaSeconds();
        return new Snapshot(step, fraction, I18n.tr("progress.bytes", percent, mb(done), mb(total),
                mb(speed()), eta < 0 ? "-" : eta(eta)));
    }

    /** Megabytes with one decimal, dot separator whatever the system locale. */
    static String mb(double bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / MB);
    }

    /** {@code 45 s}, {@code 3 min 05 s}, {@code 2 h 10 min} (localised units). */
    static String eta(long seconds) {
        if (seconds < 60) {
            return I18n.tr("progress.eta.s", seconds);
        }
        if (seconds < 3600) {
            return I18n.tr("progress.eta.min", seconds / 60, String.format(Locale.ROOT, "%02d", seconds % 60));
        }
        return I18n.tr("progress.eta.h", seconds / 3600, String.format(Locale.ROOT, "%02d", seconds % 3600 / 60));
    }
}
