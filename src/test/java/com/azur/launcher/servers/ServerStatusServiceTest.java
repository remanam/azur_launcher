package com.azur.launcher.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ServerStatusServiceTest {

    private static final ServerEntry ENTRY = new ServerEntry("a", "A", "a.example", 25565, "", "", "", "", "", false, "");

    private final Queue<Runnable> queue = new ArrayDeque<>();
    private final Executor manual = queue::add;
    private final AtomicInteger pings = new AtomicInteger();
    private final AtomicLong clock = new AtomicLong(1000);
    private final ServerStatusService service = new ServerStatusService(manual, entry -> {
        pings.incrementAndGet();
        return new ServerPing.Result(true, 1, 10, 5, "", "");
    }, clock::get, 15_000);

    @Test
    void doesNotPingTwiceWhileOneIsInFlight() {
        List<ServerPing.Result> got = new ArrayList<>();
        service.request(ENTRY, got::add);
        service.request(ENTRY, got::add);
        assertEquals(1, queue.size());
        queue.poll().run();
        assertEquals(1, pings.get());
        assertEquals(1, got.size());
    }

    @Test
    void throttlesAndServesTheCachedResult() {
        List<ServerPing.Result> got = new ArrayList<>();
        service.request(ENTRY, got::add);
        queue.poll().run();
        clock.addAndGet(5_000);
        service.request(ENTRY, got::add);
        assertEquals(0, queue.size());
        assertEquals(2, got.size());
        clock.addAndGet(11_000);
        service.request(ENTRY, got::add);
        assertEquals(1, queue.size());
    }
}
