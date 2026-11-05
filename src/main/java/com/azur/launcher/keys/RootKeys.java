package com.azur.launcher.keys;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The two root public keys embedded in the jar ({@code root_keys.txt}: lines {@code primary <base64>} and
 * {@code reserve <base64>}, {@code #} comments). They sign only the keyset ({@link Keyset}). A jar built without
 * roots (dev, or a build before the keyset release) has {@link #NONE} and keeps the single-key legacy path.
 */
public record RootKeys(byte[] primary, byte[] reserve) {

    public static final RootKeys NONE = new RootKeys(null, null);
    private static final String RESOURCE = "/root_keys.txt";

    public boolean present() {
        return primary != null && reserve != null;
    }

    /** @throws IllegalArgumentException for a malformed file; a file with no keys at all is {@link #NONE} */
    public static RootKeys parse(String text) {
        byte[] primary = null;
        byte[] reserve = null;
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] f = l.split("\\s+");
            if (f.length != 2) {
                throw new IllegalArgumentException("root_keys: bad line");
            }
            switch (f[0]) {
                case "primary" -> primary = Ed25519.raw(f[1]);
                case "reserve" -> reserve = Ed25519.raw(f[1]);
                default -> throw new IllegalArgumentException("root_keys: unknown name " + f[0]);
            }
        }
        if (primary == null && reserve == null) {
            return NONE;
        }
        if (primary == null || reserve == null || Arrays.equals(primary, reserve)) {
            throw new IllegalArgumentException("root_keys: need two different keys, primary and reserve");
        }
        return new RootKeys(primary, reserve);
    }

    /** The roots of this jar. A missing resource is {@link #NONE}. */
    public static RootKeys embedded() throws IOException {
        try (InputStream in = RootKeys.class.getResourceAsStream(RESOURCE)) {
            return in == null ? NONE : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
