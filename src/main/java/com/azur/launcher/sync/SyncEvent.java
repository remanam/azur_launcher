package com.azur.launcher.sync;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

import java.util.Optional;

/** One JSON line printed by {@code azur-updater sync|verify} (see tools/updater/README.md). */
public record SyncEvent(
        String event,
        String file,
        long done,
        long total,
        @SerializedName("pack_version") String packVersion,
        String code,
        String message,
        int exit) {

    private static final Gson GSON = new Gson();

    /** Parses one stdout line; anything that is not an event object (log noise) gives empty. */
    public static Optional<SyncEvent> parse(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("{")) {
            return Optional.empty();
        }
        try {
            SyncEvent event = GSON.fromJson(trimmed, SyncEvent.class);
            return event == null || event.event() == null ? Optional.empty() : Optional.of(event);
        } catch (JsonSyntaxException e) {
            return Optional.empty();
        }
    }
}
