package com.azur.launcher.update;

import java.util.function.LongPredicate;

/**
 * The new copy started by {@link Restarter} must not touch {@code updates/current.jar} while the old process is still
 * alive: on Windows the old JVM keeps the file open and the move fails. The old pid comes in
 * {@link Restarter#WAIT_ENV}; the bootstrap waits (bounded) until that process is gone, then applies the update.
 */
public final class RestartWait {

    /** Longest wait for the old process; after that the bootstrap goes on (its own retries still apply). */
    public static final long MAX_WAIT_MILLIS = 15_000;
    private static final long POLL_MILLIS = 200;

    /** Sleeping, replaceable in tests. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private RestartWait() {
    }

    /** Pid from the env value, or -1 when absent or not a number. */
    static long parsePid(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            long pid = Long.parseLong(value.trim());
            return pid > 0 ? pid : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Waits until the old process is gone; true when it is gone (or there was nothing to wait for). */
    public static boolean awaitParent(String envValue, LongPredicate alive, Sleeper sleeper, long maxMillis) {
        long pid = parsePid(envValue);
        if (pid < 0) {
            return true;
        }
        long waited = 0;
        while (alive.test(pid)) {
            if (waited >= maxMillis) {
                return false;
            }
            try {
                sleeper.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            waited += POLL_MILLIS;
        }
        return true;
    }

    /** Production wait: the real process table and {@link Thread#sleep}. */
    public static boolean awaitParent(String envValue) {
        return awaitParent(envValue, pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                Thread::sleep, MAX_WAIT_MILLIS);
    }

    /** Runs {@code action} up to {@code attempts} times while it throws UncheckedIOException (locked files); the last one is rethrown. */
    public static <T> T retrying(int attempts, Sleeper sleeper, java.util.function.Supplier<T> action) {
        for (int i = 1; ; i++) {
            try {
                return action.get();
            } catch (java.io.UncheckedIOException e) {
                if (i >= attempts) {
                    throw e;
                }
                try {
                    sleeper.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
