package com.azur.launcher.ui;

import com.azur.launcher.feed.FeedService;
import com.azur.launcher.keys.KeysetService;

import javafx.application.Platform;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Reads the notice feed (and refreshes the keyset first) 2 s after start and every 15 minutes while the launcher is open (not while the game runs). */
final class FeedController {

    private static final long FIRST_DELAY_SECONDS = 2;
    private static final long PERIOD_MINUTES = 15;

    private final FeedService service;
    private final KeysetService keysets;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "azur-feed");
        thread.setDaemon(true);
        return thread;
    });
    private Consumer<com.azur.launcher.feed.FeedDocument> listener = doc -> { };

    private FeedController(FeedService service, KeysetService keysets) {
        this.service = service;
        this.keysets = keysets;
    }

    /** Null without a servers URL (dev run) or without the key. The feed host is the host of the servers URL. */
    static FeedController create(AppContext ctx, BooleanSupplier gameBusy) {
        String serversUrl = ctx.pack.getServersUrl();
        if (serversUrl == null || serversUrl.isBlank()) {
            return null;
        }
        try {
            URI base = URI.create(serversUrl);
            String url = base.resolve("/dl/feed.json").toString();
            return new FeedController(new FeedService(ctx.dirs.cacheDir(), url,
                    () -> ctx.keysets.trust().serverKeys(java.time.Instant.now()), new HttpTransport(base.getHost()), gameBusy,
                    ctx::log), ctx.keysets);
        } catch (Exception e) {
            ctx.log("Feed disabled: " + e.getMessage());
            return null;
        }
    }

    /** Called on the JavaFX thread: immediately with the cached feed (if any), then after every change. */
    void onFeed(Consumer<com.azur.launcher.feed.FeedDocument> value) {
        listener = value;
        value.accept(service.current());
    }

    void start() {
        timer.scheduleWithFixedDelay(this::checkNow, FIRST_DELAY_SECONDS, PERIOD_MINUTES * 60, TimeUnit.SECONDS);
    }

    void stop() {
        timer.shutdownNow();
    }

    private void checkNow() {
        keysets.refreshIfStale(KeysetService.MAX_AGE); // an open launcher picks up key revocations on this timer too
        if (service.refresh()) {
            com.azur.launcher.feed.FeedDocument doc = service.current();
            Platform.runLater(() -> listener.accept(doc));
        }
    }

    /** The shared connection pool, https only, only the servers host, If-None-Match. */
    private static final class HttpTransport implements FeedService.Transport {
        private final String host;

        HttpTransport(String host) {
            this.host = host;
        }

        @Override
        public FeedService.Response get(String url, String etag) throws IOException {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !host.equalsIgnoreCase(uri.getHost())) {
                throw new IOException("feed host not allowed");
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET();
            if (!etag.isEmpty()) {
                request.header("If-None-Match", etag);
            }
            try {
                HttpResponse<byte[]> response = com.azur.launcher.net.SharedHttp.client().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                return new FeedService.Response(response.statusCode(), response.headers().firstValue("ETag").orElse(""),
                        response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
    }
}
