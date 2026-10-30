package com.azur.launcher.pack;

import java.util.Base64;

/**
 * Where a server's pack comes from: base URL and the base64 Ed25519 public key that signs its manifest. Both come from
 * the {@code pack} field of the signed server list entry (docs/SERVERS_JSON.md); the pack download itself is open.
 */
public record PackSource(String url, String key) {

    public static final PackSource NONE = new PackSource("", "");

    public PackSource {
        url = url == null ? "" : url.strip();
        key = key == null ? "" : key.strip();
    }

    public boolean present() {
        return !url.isEmpty() && !key.isEmpty();
    }

    public PackSource withUrl(String newUrl) {
        return new PackSource(newUrl, key);
    }

    /** The 32 raw key bytes. @throws PackException code {@code nopack} when the entry has no usable key */
    byte[] publicKey() throws PackException {
        try {
            byte[] raw = Base64.getDecoder().decode(key);
            if (raw.length == 32) {
                return raw;
            }
        } catch (IllegalArgumentException ignored) {
            // reported below
        }
        throw new PackException(PackException.NO_PACK, "the server list gives no valid pack key for this server");
    }
}
