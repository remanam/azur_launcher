package com.azur.launcher.feed;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;

/** Builds signed feed.json files the way tools/updater (Go, feedman) does. */
final class FeedTestSupport {

    private FeedTestSupport() {
    }

    static KeyPair newKey() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static FeedNotice notice(String id, FeedNotice.Severity severity) {
        return new FeedNotice(id, severity, new FeedText("Заголовок " + id, "Title " + id), new FeedText("Текст", "Text"),
                "", "", "", "", "", List.of(), List.of(), "");
    }

    /** The same document as the Go test {@code sample()}. */
    static FeedDocument sample() {
        FeedNotice n = new FeedNotice("n1", FeedNotice.Severity.WARN, new FeedText("Заголовок", "Title"),
                new FeedText("Текст\nстрока", "Text"), "https://example.test/x", "2026-11-01", "2026-11-09", "1.0.0",
                "2.0.0", List.of("mac", "linux"), List.of("isles"), "");
        FeedNews news = new FeedNews("a", new FeedText("Новость", ""), "2026-10-30", "https://example.test/news/a");
        return new FeedDocument("2026-11-02T12:00:00Z", List.of(n), List.of(news));
    }

    static byte[] signed(FeedDocument doc, KeyPair key) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(doc.signedMessage().getBytes(StandardCharsets.UTF_8));
        return json(doc, Base64.getEncoder().encodeToString(signer.sign())).getBytes(StandardCharsets.UTF_8);
    }

    static String json(FeedDocument doc, String signature) {
        JsonObject root = new JsonObject();
        root.addProperty("generated", doc.generated());
        JsonArray notices = new JsonArray();
        for (FeedNotice n : doc.notices()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", n.id());
            o.addProperty("severity", n.severity().wire());
            o.add("title", text(n.title()));
            o.add("text", text(n.text()));
            o.addProperty("link", n.link());
            o.addProperty("starts", n.starts());
            o.addProperty("expires", n.expires());
            o.addProperty("min_launcher", n.minLauncher());
            o.addProperty("max_launcher", n.maxLauncher());
            o.add("os", new Gson().toJsonTree(n.os()));
            o.add("server_id", new Gson().toJsonTree(n.serverIds()));
            o.addProperty("account", n.account());
            notices.add(o);
        }
        root.add("notices", notices);
        JsonArray news = new JsonArray();
        for (FeedNews n : doc.news()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", n.id());
            o.add("title", text(n.title()));
            o.addProperty("date", n.date());
            o.addProperty("link", n.link());
            news.add(o);
        }
        root.add("news", news);
        root.addProperty("signature", signature);
        return root.toString();
    }

    private static JsonObject text(FeedText t) {
        JsonObject o = new JsonObject();
        o.addProperty("ru", t.ru());
        o.addProperty("en", t.en());
        return o;
    }
}
