package com.azur.launcher.servers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Throttled status pings: a server is pinged at most once per interval, and never twice at the same time.
 * Pings run on the given executor (never the FX thread); the callback runs on that executor's thread.
 */
public final class ServerStatusService {

    /** Ping function, replaceable in tests. */
    @FunctionalInterface
    public interface Pinger {
        ServerPing.Result ping(ServerEntry entry);
    }

    private static final int PING_TIMEOUT_MS = 3000;

    private final Executor executor;
    private final Pinger pinger;
    private final LongSupplier millis;
    private final long minIntervalMs;
    private final Map<String, Long> lastPing = new HashMap<>();
    private final Map<String, ServerPing.Result> lastResult = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();

    public ServerStatusService(Executor executor, long minIntervalMs) {
        this(executor, entry -> ServerPing.ping(entry.address(), entry.port(), PING_TIMEOUT_MS),
                System::currentTimeMillis, minIntervalMs);
    }

    ServerStatusService(Executor executor, Pinger pinger, LongSupplier millis, long minIntervalMs) {
        this.executor = executor;
        this.pinger = pinger;
        this.millis = millis;
        this.minIntervalMs = minIntervalMs;
    }

    /** Delivers the cached result at once when it is fresh; otherwise pings in the background (if not already). */
    public synchronized void request(ServerEntry entry, Consumer<ServerPing.Result> callback) {
        long now = millis.getAsLong();
        Long last = lastPing.get(entry.id());
        if (Objects.nonNull(last) && now - last < minIntervalMs) {
            ServerPing.Result cached = lastResult.get(entry.id());
            if (Objects.nonNull(cached)) {
                callback.accept(cached);
            }
            return;
        }
        if (!inFlight.add(entry.id())) {
            return;
        }
        lastPing.put(entry.id(), now);
        executor.execute(() -> {
            ServerPing.Result result = pinger.ping(entry);
            synchronized (this) {
                lastResult.put(entry.id(), result);
                inFlight.remove(entry.id());
            }
            callback.accept(result);
        });
    }

    /** Forgets everything (new server list). */
    public synchronized void reset() {
        lastPing.clear();
        lastResult.clear();
    }
}
