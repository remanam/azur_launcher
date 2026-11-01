package com.azur.launcher.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;

public class Arguments {

    private List<Entry> game;
    private List<Entry> jvm;

    public List<Entry> getGame() {
        return game;
    }

    public List<Entry> getJvm() {
        return jvm;
    }

    /** Loader JSON arguments are appended to the parent's (vanilla's), never replace them. */
    public static Arguments merge(Arguments parent, Arguments child) {
        if (parent == null || child == null) {
            return child != null ? child : parent;
        }
        Arguments merged = new Arguments();
        merged.game = concat(parent.game, child.game);
        merged.jvm = concat(parent.jvm, child.jvm);
        return merged;
    }

    private static List<Entry> concat(List<Entry> first, List<Entry> second) {
        List<Entry> all = new java.util.ArrayList<>();
        if (first != null) {
            all.addAll(first);
        }
        if (second != null) {
            all.addAll(second);
        }
        return all;
    }

    @JsonAdapter(EntryDeserializer.class)
    public static class Entry {
        private List<Rule> rules;
        private JsonElement value;

        public List<Rule> getRules() {
            return rules;
        }

        public JsonElement getValue() {
            return value;
        }
    }

    public static class EntryDeserializer implements JsonDeserializer<Entry> {
        @Override
        public Entry deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) {
            Entry entry = new Entry();
            if (json == null || json.isJsonNull()) {
                return entry;
            }
            if (json.isJsonObject()) {
                JsonObject object = json.getAsJsonObject();
                entry.value = object.get("value");
                JsonElement rules = object.get("rules");
                if (rules != null && rules.isJsonArray()) {
                    entry.rules = context.deserialize(rules, new TypeToken<List<Rule>>() { }.getType());
                }
            } else {
                entry.value = json;
            }
            return entry;
        }
    }
}
