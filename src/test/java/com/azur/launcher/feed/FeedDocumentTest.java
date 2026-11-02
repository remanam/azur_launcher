package com.azur.launcher.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azur.launcher.update.UpdateException;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

class FeedDocumentTest {

    /** The exact text is a contract with Go (feedman TestMessageFormat pins the same string). */
    @Test
    void signedMessageMatchesGo() {
        String want = "azur-feed/1\n20:2026-11-02T12:00:00Z\n"
                + "notice 2:n14:warn18:Заголовок5:Title23:Текст\nстрока4:Text22:https://example.test/x10:2026-11-0110:2026-11-095:1.0.05:2.0.09:mac,linux5:isles0:\n"
                + "news 1:a14:Новость0:10:2026-10-3027:https://example.test/news/a";
        assertEquals(want, FeedTestSupport.sample().signedMessage());
    }

    @Test
    void goSignedFeedOpens() throws Exception {
        KeyPair key = FeedTestSupport.newKey();
        FeedDocument opened = FeedDocument.open(FeedTestSupport.signed(FeedTestSupport.sample(), key), key.getPublic());
        assertEquals(FeedTestSupport.sample(), opened);
    }

    @Test
    void tamperedWrongKeyAndJunkAreRejected() throws Exception {
        KeyPair key = FeedTestSupport.newKey();
        String signed = new String(FeedTestSupport.signed(FeedTestSupport.sample(), key), StandardCharsets.UTF_8);
        byte[] tampered = signed.replace("Заголовок", "Другое").getBytes(StandardCharsets.UTF_8);
        assertThrows(UpdateException.class, () -> FeedDocument.open(tampered, key.getPublic()));
        assertThrows(UpdateException.class, () -> FeedDocument.open(signed.getBytes(StandardCharsets.UTF_8),
                FeedTestSupport.newKey().getPublic()));
        assertThrows(UpdateException.class, () -> FeedDocument.open("[]".getBytes(StandardCharsets.UTF_8), key.getPublic()));
    }
}
