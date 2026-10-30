package com.azur.launcher.pack;

/** Progress of a pack run; may be called from worker threads. */
@FunctionalInterface
public interface PackProgress {

    PackProgress NONE = (phase, file, done, total) -> { };

    /** @param phase {@code download} (bytes) or {@code install} (files); {@code file} is the pack-relative path. */
    void onProgress(String phase, String file, long done, long total);
}
