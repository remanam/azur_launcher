package com.azur.launcher.update;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;

/** Builds signed launcher.json files the way tools/updater (Go) does. */
final class UpdateTestSupport {

    static final String URL = "https://example.test/dl/azur-launcher.jar";

    private UpdateTestSupport() {
    }

    static KeyPair newKey() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    static String json(UpdateManifest m, String signature) {
        return "{\"version\":\"" + m.version() + "\",\"sha256\":\"" + m.sha256() + "\",\"size\":" + m.size()
                + ",\"date\":\"" + m.date() + "\",\"url\":\"" + m.url() + "\",\"min_wrapper\":\"" + m.minWrapper()
                + "\",\"required\":" + m.required() + ",\"notes\":\"" + m.notes() + "\",\"signature\":\"" + signature + "\"}";
    }

    static byte[] signed(UpdateManifest m, KeyPair key) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(m.signedMessage().getBytes(StandardCharsets.UTF_8));
        return json(m, Base64.getEncoder().encodeToString(signer.sign())).getBytes(StandardCharsets.UTF_8);
    }

    static UpdateManifest manifest(String version, byte[] jar, String minWrapper, boolean required) throws Exception {
        return new UpdateManifest(version, sha256(jar), jar.length, "2026-11-02", URL, minWrapper, required, "notes");
    }
}
