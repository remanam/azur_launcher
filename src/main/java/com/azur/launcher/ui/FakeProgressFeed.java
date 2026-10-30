package com.azur.launcher.ui;

import com.azur.launcher.sync.PackSyncService;

/**
 * Dev-only progress feed behind {@code -Dazur.fakeProgress=true}: walks all install steps in about 20 seconds with
 * made-up byte counts so the bar can be looked at without a download. Absent from the user interface.
 */
final class FakeProgressFeed {

    static final String PROPERTY = "azur.fakeProgress";
    private static final long MB = 1024 * 1024;
    private static final long PACK_BYTES = 400 * MB;

    private FakeProgressFeed() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Blocking; call off the UI thread. */
    static PackOps.Outcome run(InstallProgress progress) {
        try {
            progress.step(InstallProgress.Step.LIBRARIES);
            for (int i = 0; i <= 10; i++) {
                progress.files(InstallProgress.Step.LIBRARIES, i, 10);
                Thread.sleep(100);
            }
            progress.step(InstallProgress.Step.NEOFORGE);
            Thread.sleep(2500);
            for (int i = 0; i <= 20; i++) {
                progress.files(InstallProgress.Step.MINECRAFT, i * 10, 200);
                Thread.sleep(100);
            }
            progress.step(InstallProgress.Step.CHECK);
            Thread.sleep(500);
            for (int i = 0; i <= 120; i++) {
                progress.bytes(InstallProgress.Step.PACK, PACK_BYTES * i / 120, PACK_BYTES);
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new PackOps.Outcome(new PackSyncService.Result(0, "", "", ""), false, false);
    }
}
