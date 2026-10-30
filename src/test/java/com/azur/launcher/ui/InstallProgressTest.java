package com.azur.launcher.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azur.launcher.ui.InstallProgress.Step;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class InstallProgressTest {

    private static final long MB = 1024 * 1024;
    private final AtomicLong now = new AtomicLong();
    private final InstallProgress progress = new InstallProgress(now::get);

    private void tick(long millis, long done, long total) {
        now.addAndGet(millis * 1_000_000L);
        progress.bytes(Step.PACK, done, total);
    }

    @Test
    void speedIsAveragedOverTheLastTwoSeconds() {
        tick(0, 0, 100 * MB);
        tick(500, 5 * MB, 100 * MB);
        tick(500, 10 * MB, 100 * MB);
        tick(500, 15 * MB, 100 * MB);
        tick(500, 20 * MB, 100 * MB);
        assertEquals(10.0, progress.speed() / MB, 0.01);
        // a stall: the old fast samples leave the window
        tick(2500, 20 * MB, 100 * MB);
        tick(500, 20 * MB, 100 * MB);
        assertEquals(0.0, progress.speed(), 1.0);
    }

    @Test
    void etaFollowsSpeedAndIsUnknownWithoutIt() {
        tick(0, 0, 100 * MB);
        assertEquals(-1, progress.etaSeconds());
        tick(1000, 10 * MB, 100 * MB);
        assertEquals(9, progress.etaSeconds());
    }

    @Test
    void snapshotShowsPercentAndMegabytes() {
        tick(0, 0, 200 * MB);
        tick(1000, 50 * MB, 200 * MB);
        InstallProgress.Snapshot s = progress.snapshot();
        assertEquals(Step.PACK, s.step());
        assertEquals(0.25, s.fraction(), 1e-9);
        assertTrue(s.detail().contains("25"), s.detail());
        assertTrue(s.detail().contains("50.0"), s.detail());
        assertTrue(s.detail().contains("200.0"), s.detail());
    }

    @Test
    void stepWithoutCountsIsIndeterminateNotZeroPercent() {
        progress.step(Step.NEOFORGE);
        InstallProgress.Snapshot s = progress.snapshot();
        assertEquals(Step.NEOFORGE, s.step());
        assertEquals(-1.0, s.fraction());
        assertEquals("", s.detail());
    }

    @Test
    void fileCountsAreShownAsFilesNotBytes() {
        progress.files(Step.MINECRAFT, 30, 120);
        InstallProgress.Snapshot s = progress.snapshot();
        assertEquals(0.25, s.fraction(), 1e-9);
        assertTrue(s.detail().contains("30") && s.detail().contains("120"), s.detail());
        assertEquals(-1, progress.etaSeconds());
    }

    @Test
    void changingStepResetsTheSpeedWindow() {
        tick(0, 0, 10 * MB);
        tick(1000, 5 * MB, 10 * MB);
        progress.files(Step.MINECRAFT, 1, 10);
        assertEquals(0.0, progress.speed());
    }

    @Test
    void etaFormatting() {
        assertEquals("45 с", InstallProgress.eta(45));
        assertEquals("3 мин 05 с", InstallProgress.eta(185));
        assertEquals("2 ч 10 мин", InstallProgress.eta(7800));
        assertEquals("12.5", InstallProgress.mb(12.5 * MB));
    }

    @Test
    void freshFlagDrivesTheButtonWording() {
        assertEquals("home.btn.installing", HomeState.busyLabelKey(false, true));
        assertEquals("home.btn.updating", HomeState.busyLabelKey(false, false));
        assertEquals("home.btn.checking", HomeState.busyLabelKey(true, true));
    }
}
