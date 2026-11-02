package com.azur.launcher.feed;

import com.google.gson.JsonObject;

/** One headline for the home screen. */
public record FeedNews(String id, FeedText title, String date, String link) {

    static FeedNews parse(JsonObject json) {
        return new FeedNews(FeedText.str(json, "id"), FeedText.parse(json, "title"), FeedText.str(json, "date"),
                FeedText.str(json, "link"));
    }
}
