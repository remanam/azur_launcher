package com.azur.launcher.feed;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * One announcement. Empty restriction fields mean "everyone". {@code account} is reserved for personal notices
 * (stage 3): it is signed, and a notice that has it is never shown to anyone yet.
 */
public record FeedNotice(String id, Severity severity, FeedText title, FeedText text, String link, String starts,
                         String expires, String minLauncher, String maxLauncher, List<String> os,
                         List<String> serverIds, String account) {

    public enum Severity {
        INFO("info"), WARN("warn"), CRITICAL("critical");

        private final String wire;

        Severity(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        static Severity of(String wire) {
            for (Severity s : values()) {
                if (s.wire.equals(wire)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("severity " + wire);
        }
    }

    static FeedNotice parse(JsonObject json) {
        return new FeedNotice(FeedText.str(json, "id"), Severity.of(FeedText.str(json, "severity")),
                FeedText.parse(json, "title"), FeedText.parse(json, "text"), FeedText.str(json, "link"),
                FeedText.str(json, "starts"), FeedText.str(json, "expires"), FeedText.str(json, "min_launcher"),
                FeedText.str(json, "max_launcher"), FeedText.list(json, "os"), FeedText.list(json, "server_id"),
                FeedText.str(json, "account"));
    }
}
