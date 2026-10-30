package com.azur.launcher.sync;

/** Receives progress during a pack sync; called on the sync thread. */
@FunctionalInterface
public interface SyncProgress {

    /** @param phase {@code download} or {@code install}; {@code file} is the pack-relative path. */
    void onProgress(String phase, String file, long done, long total);
}
