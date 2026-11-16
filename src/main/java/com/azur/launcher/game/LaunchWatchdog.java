package com.azur.launcher.game;

import java.util.Objects;

/**
 * Pure hang-detection logic for the game start: no log growth for {@code hangSeconds} or no "loaded" marker within
 * {@code startupLimitSeconds} means a hang. Times are milliseconds on any monotonic clock.
 */
public final class LaunchWatchdog {

    /** Line of latest.log that appears once resources are loaded and the main menu is about to show. */
    public static final String READY_MARKER = "Sound engine started";

    private final long hangMs;
    private final long limitMs;
    private final long startedAt;
    private long lastGrowthAt;
    private long lastSize = -1;
    private boolean ready;
    private long extraUntil;

    public LaunchWatchdog(int hangSeconds, int startupLimitSeconds, long nowMs) {
        this.hangMs = hangSeconds * 1000L;
        this.limitMs = startupLimitSeconds * 1000L;
        this.startedAt = nowMs;
        this.lastGrowthAt = nowMs;
    }

    public static boolean isReadyLine(String line) {
        return Objects.nonNull(line) && line.contains(READY_MARKER);
    }

    public void markReady() {
        ready = true;
    }

    public boolean ready() {
        return ready;
    }

    /** Feed the current total size of the watched logs; a change counts as progress. */
    public void observeSize(long size, long nowMs) {
        if (size != lastSize) {
            lastSize = size;
            lastGrowthAt = nowMs;
        }
    }

    /** The user chose to wait: no new alarm before {@code ms} from now. */
    public void postpone(long ms, long nowMs) {
        extraUntil = nowMs + ms;
        lastGrowthAt = nowMs;
    }

    /** True when the start looks hung. Disabled (false) once ready or when both limits are 0. */
    public boolean hung(long nowMs) {
        if (ready || nowMs < extraUntil) {
            return false;
        }
        boolean stalled = hangMs > 0 && nowMs - lastGrowthAt >= hangMs;
        boolean tooLong = limitMs > 0 && nowMs - startedAt >= limitMs;
        return stalled || tooLong;
    }
}
