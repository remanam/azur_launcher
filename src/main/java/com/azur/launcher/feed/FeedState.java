package com.azur.launcher.feed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the player already saw: ids of closed banners and of read notices, in {@code <data>/feed_state.txt}
 * ("closed id" / "read id" lines). A critical notice cannot be closed: it stays until it expires or is removed.
 */
public final class FeedState {

    private static final int MAX_IDS = 500;

    private final Path file;
    private final Set<String> closed = new LinkedHashSet<>();
    private final Set<String> read = new LinkedHashSet<>();

    public FeedState(Path file) {
        this.file = file;
        try {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file)) {
                    if (line.startsWith("closed ")) {
                        closed.add(line.substring(7));
                    } else if (line.startsWith("read ")) {
                        read.add(line.substring(5));
                    }
                }
            }
        } catch (IOException ignored) {
            // a lost state only shows a notice once more
        }
    }

    public synchronized boolean isClosed(FeedNotice n) {
        return n.severity() != FeedNotice.Severity.CRITICAL && closed.contains(n.id());
    }

    public synchronized boolean isRead(String id) {
        return read.contains(id);
    }

    public synchronized void close(FeedNotice n) {
        if (n.severity() != FeedNotice.Severity.CRITICAL && closed.add(n.id())) {
            read.add(n.id());
            save();
        }
    }

    public synchronized void markRead(Iterable<FeedNotice> notices) {
        boolean changed = false;
        for (FeedNotice n : notices) {
            changed |= read.add(n.id());
        }
        if (changed) {
            save();
        }
    }

    private void save() {
        trim(closed);
        trim(read);
        StringBuilder text = new StringBuilder();
        closed.forEach(id -> text.append("closed ").append(id).append('\n'));
        read.forEach(id -> text.append("read ").append(id).append('\n'));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text.toString());
        } catch (IOException ignored) {
            // see constructor
        }
    }

    private static void trim(Set<String> ids) {
        while (ids.size() > MAX_IDS) {
            ids.remove(ids.iterator().next());
        }
    }
}
