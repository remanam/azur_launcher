package com.azur.launcher.keys;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Builds keyset.json envelopes the way {@code azur-servers keyset sign} does (temporary test keys only). */
public final class KeysetTestSupport {

    public static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    public final KeyPair primary = newKey();
    public final KeyPair reserve = newKey();
    public final RootKeys roots = new RootKeys(raw(primary), raw(reserve));

    /** A keyset document under construction. */
    public static final class Doc {
        long epoch;
        long version = 1;
        Instant expires = NOW.plusSeconds(45L * 86400);
        final List<String> keys = new ArrayList<>();
        final List<String> revoked = new ArrayList<>();

        public Doc epoch(long value) {
            epoch = value;
            return this;
        }

        public Doc version(long value) {
            version = value;
            return this;
        }

        public Doc expires(Instant value) {
            expires = value;
            return this;
        }

        public Doc key(String id, String role, KeyPair key, Instant keyExpires) {
            keys.add("{\"id\":\"" + id + "\",\"role\":\"" + role + "\",\"key\":\"" + b64(key) + "\",\"expires_at\":\""
                    + keyExpires + "\"}");
            return this;
        }

        public Doc key(String id, String role, KeyPair key) {
            return key(id, role, key, NOW.plusSeconds(30L * 86400));
        }

        public Doc revoke(String idOrKey) {
            revoked.add("\"" + idOrKey + "\"");
            return this;
        }

        String payload() {
            return "{\"type\":\"azur-keyset/1\",\"epoch\":" + epoch + ",\"version\":" + version + ",\"expires_at\":\""
                    + expires + "\",\"keys\":[" + String.join(",", keys) + "],\"revoked\":[" + String.join(",", revoked) + "]}";
        }
    }

    public static Doc doc() {
        return new Doc();
    }

    public static KeyPair newKey() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] raw(KeyPair key) {
        return Ed25519.raw(key.getPublic());
    }

    public static String b64(KeyPair key) {
        return Base64.getEncoder().encodeToString(raw(key));
    }

    public static String sign(PrivateKey key, byte[] message) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(message);
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The envelope signed by each of {@code signers}. */
    public static byte[] envelope(Doc doc, KeyPair... signers) {
        byte[] payload = doc.payload().getBytes(StandardCharsets.UTF_8);
        List<String> sigs = new ArrayList<>();
        for (KeyPair k : signers) {
            sigs.add("{\"key_id\":\"" + Ed25519.keyId(raw(k)) + "\",\"sig\":\"" + sign(k.getPrivate(), payload) + "\"}");
        }
        return ("{\"payload\":\"" + Base64.getEncoder().encodeToString(payload) + "\",\"sigs\":[" + String.join(",", sigs) + "]}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
