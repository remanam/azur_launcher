package com.azur.launcher.sync;

/** Receives progress while the updater process runs; called on the thread that reads its output. */
@FunctionalInterface
public interface SyncProgress {

    /** @param phase {@code download} or {@code install}; {@code file} is the pack-relative path. */
    void onProgress(String phase, String file, long done, long total);
}
