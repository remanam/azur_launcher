package com.azur.launcher.feed;

import com.google.gson.JsonObject;

/** A ru/en pair; Russian is the default, so a missing English text falls back to it. */
public record FeedText(String ru, String en) {

    static FeedText parse(JsonObject owner, String name) {
        if (!owner.has(name)) {
            return new FeedText("", "");
        }
        JsonObject json = owner.getAsJsonObject(name);
        return new FeedText(str(json, "ru"), str(json, "en"));
    }

    static String str(JsonObject json, String name) {
        return json.has(name) && !json.get(name).isJsonNull() ? json.get(name).getAsString() : "";
    }

    static java.util.List<String> list(JsonObject json, String name) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (json.has(name)) {
            json.getAsJsonArray(name).forEach(e -> out.add(e.getAsString()));
        }
        return java.util.List.copyOf(out);
    }

    /** The text for {@code language} ("en" or anything else = ru). */
    public String in(String language) {
        return "en".equals(language) && !en.isBlank() ? en : ru;
    }
}
