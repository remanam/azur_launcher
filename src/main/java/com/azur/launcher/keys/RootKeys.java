package com.azur.launcher.keys;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The root public keys embedded in the jar ({@code root_keys.txt}, one root per line: {@code working <base64>} (root A,
 * signs the monthly keyset) or {@code emergency <base64>} (roots B and C, kept in two other places); the old names
 * {@code primary}/{@code reserve} mean the same; a bare {@code <base64>} line is the working root when it is the first key
 * line, an emergency root otherwise; {@code #} comments). At least one working and one emergency root, all different; we
 * ship three. They sign only the keyset ({@link Keyset}). A jar built without roots (dev) has {@link #NONE} and keeps the
 * single-key legacy path. Twin of Go {@code keyset.ParseRoots}.
 */
public record RootKeys(List<byte[]> working, List<byte[]> emergency) {

    public static final RootKeys NONE = new RootKeys(List.of(), List.of());
    private static final String RESOURCE = "/root_keys.txt";

    public RootKeys {
        working = List.copyOf(working);
        emergency = List.copyOf(emergency);
    }

    public boolean present() {
        return !working.isEmpty() && !emergency.isEmpty();
    }

    /** Every embedded root, working first. */
    public List<byte[]> all() {
        List<byte[]> out = new ArrayList<>(working);
        out.addAll(emergency);
        return out;
    }

    /** @throws IllegalArgumentException for a malformed file; a file with no keys at all is {@link #NONE} */
    public static RootKeys parse(String text) {
        List<byte[]> working = new ArrayList<>();
        List<byte[]> emergency = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] f = l.split("\\s+");
            String role;
            String key;
            if (f.length == 1) {
                role = seen.isEmpty() ? "working" : "emergency";
                key = f[0];
            } else if (f.length == 2) {
                role = f[0];
                key = f[1];
            } else {
                throw new IllegalArgumentException("root_keys: bad line");
            }
            byte[] raw = Ed25519.raw(key);
            if (!seen.add(Base64.getEncoder().encodeToString(raw))) {
                throw new IllegalArgumentException("root_keys: the same key is listed twice");
            }
            switch (role) {
                case "working", "primary" -> working.add(raw);
                case "emergency", "reserve" -> emergency.add(raw);
                default -> throw new IllegalArgumentException("root_keys: unknown role " + role);
            }
        }
        if (seen.isEmpty()) {
            return NONE;
        }
        if (working.isEmpty() || emergency.isEmpty()) {
            throw new IllegalArgumentException("root_keys: need at least one working and one emergency root");
        }
        return new RootKeys(working, emergency);
    }

    /** The roots of this jar. A missing resource is {@link #NONE}. */
    public static RootKeys embedded() throws IOException {
        try (InputStream in = RootKeys.class.getResourceAsStream(RESOURCE)) {
            return Objects.isNull(in) ? NONE : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
