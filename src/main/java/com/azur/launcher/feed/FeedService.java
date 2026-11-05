package com.azur.launcher.feed;

import com.azur.launcher.update.UpdateException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reads the signed feed: conditional GET (If-None-Match, a 304 costs almost nothing), signature check, and the last
 * valid feed cached in the data folder. A bad signature or a network error keeps whatever was valid before.
 * Nothing is fetched while the game runs or prepares.
 */
public final class FeedService {

    /** One HTTP answer: 200 with body and etag, or 304. */
    public record Response(int status, String etag, byte[] body) {
    }

    /** The network (HttpClient in the app, a fake in tests). */
    public interface Transport {
        Response get(String url, String etag) throws IOException;
    }

    private final Path cacheFile;
    private final Path etagFile;
    private final String url;
    private final Supplier<List<PublicKey>> keys;
    private final Transport transport;
    private final BooleanSupplier gameBusy;
    private final Consumer<String> log;
    private FeedDocument current;
    private String etag = "";

    public FeedService(Path cacheDir, String url, PublicKey key, Transport transport, BooleanSupplier gameBusy,
                       Consumer<String> log) {
        this(cacheDir, url, () -> List.of(key), transport, gameBusy, log);
    }

    /** {@code keys} is read at every check: the servers keys of the current keyset (or the legacy key). */
    public FeedService(Path cacheDir, String url, Supplier<List<PublicKey>> keys, Transport transport,
                       BooleanSupplier gameBusy, Consumer<String> log) {
        this.cacheFile = cacheDir.resolve("feed.json");
        this.etagFile = cacheDir.resolve("feed.etag");
        this.url = url;
        this.keys = keys;
        this.transport = transport;
        this.gameBusy = gameBusy;
        this.log = log;
        loadCache();
    }

    /** The last valid feed (cache or network), or null. */
    public synchronized FeedDocument current() {
        return current;
    }

    /** One refresh; returns true if the feed changed. Safe to call from a background thread. */
    public synchronized boolean refresh() {
        if (gameBusy.getAsBoolean()) {
            return false;
        }
        try {
            Response response = transport.get(url, current == null ? "" : etag);
            if (response.status() == 304 && current != null) {
                return false;
            }
            if (response.status() != 200) {
                log.accept("Feed: HTTP " + response.status());
                return false;
            }
            FeedDocument fresh = FeedDocument.open(response.body(), keys.get());
            boolean changed = current == null || !current.generated().equals(fresh.generated());
            current = fresh;
            etag = response.etag() == null ? "" : response.etag();
            store(response.body());
            return changed;
        } catch (UpdateException e) {
            log.accept("Feed ignored: " + e.getMessage());
        } catch (IOException e) {
            log.accept("Feed unavailable: " + e.getMessage());
        }
        return false;
    }

    private void loadCache() {
        try {
            if (Files.isRegularFile(cacheFile)) {
                current = FeedDocument.open(Files.readAllBytes(cacheFile), keys.get());
                etag = Files.isRegularFile(etagFile) ? Files.readString(etagFile).trim() : "";
            }
        } catch (IOException | UpdateException e) {
            current = null; // a damaged or foreign cache is just a missing one
        }
    }

    private void store(byte[] body) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.write(cacheFile, body);
            Files.writeString(etagFile, etag);
        } catch (IOException e) {
            log.accept("Feed cache not written: " + e.getMessage());
        }
    }
}
