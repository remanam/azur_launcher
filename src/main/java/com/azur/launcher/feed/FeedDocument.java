package com.azur.launcher.feed;

import com.azur.launcher.keys.Ed25519;
import com.azur.launcher.update.UpdateException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * {@code feed.json}: notices and news, signed with the key of servers.json. The signature covers
 * {@link #signedMessage()}, a fixed text identical to the Go signer ({@code tools/updater/internal/feedman}): every
 * string is written as {@code <utf8 byte length>:<value>}, one line per item, so no JSON canonicalisation is needed.
 */
public record FeedDocument(String generated, List<FeedNotice> notices, List<FeedNews> news) {

    private static final int MAX_BYTES = 256 * 1024;

    /** The exact bytes that are signed. */
    String signedMessage() {
        List<String> lines = new ArrayList<>();
        lines.add("azur-feed/1");
        lines.add(field(generated));
        for (FeedNotice n : notices) {
            lines.add("notice " + fields(n.id(), n.severity().wire(), n.title().ru(), n.title().en(), n.text().ru(),
                    n.text().en(), n.link(), n.starts(), n.expires(), n.minLauncher(), n.maxLauncher(),
                    String.join(",", n.os()), String.join(",", n.serverIds()), n.account()));
        }
        for (FeedNews n : news) {
            lines.add("news " + fields(n.id(), n.title().ru(), n.title().en(), n.date(), n.link()));
        }
        return String.join("\n", lines);
    }

    private static String field(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }

    private static String fields(String... values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            out.append(field(value));
        }
        return out.toString();
    }

    /** Parses and verifies; throws {@link UpdateException} for a bad format or signature. */
    public static FeedDocument open(byte[] raw, PublicKey key) throws UpdateException {
        return open(raw, List.of(key));
    }

    /** Same with any of {@code keys} (the servers keys of the keyset). */
    public static FeedDocument open(byte[] raw, List<PublicKey> keys) throws UpdateException {
        if (raw.length > MAX_BYTES) {
            throw new UpdateException("feed too large");
        }
        FeedDocument doc;
        byte[] signature;
        try {
            JsonObject json = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            List<FeedNotice> notices = new ArrayList<>();
            for (JsonElement e : array(json, "notices")) {
                notices.add(FeedNotice.parse(e.getAsJsonObject()));
            }
            List<FeedNews> news = new ArrayList<>();
            for (JsonElement e : array(json, "news")) {
                news.add(FeedNews.parse(e.getAsJsonObject()));
            }
            doc = new FeedDocument(json.get("generated").getAsString(), List.copyOf(notices), List.copyOf(news));
            signature = Base64.getDecoder().decode(json.get("signature").getAsString());
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException | NullPointerException
                 | ClassCastException e) {
            throw new UpdateException("not a feed");
        }
        if (!Ed25519.verifyAny(keys, doc.signedMessage().getBytes(StandardCharsets.UTF_8), signature)) {
            throw new UpdateException("bad signature");
        }
        return doc;
    }

    private static JsonArray array(JsonObject json, String name) {
        return json.has(name) && json.get(name).isJsonArray() ? json.getAsJsonArray(name) : new JsonArray();
    }
}
