package com.azur.launcher.pack;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Per-player invite code {@code azur1.<base64url(json {u,t,k})>}: server URL, bearer token, pack public key. */
public record InviteToken(String url, String token, byte[] publicKey) {

    private static final String PREFIX = "azur1.";
    private static final Gson GSON = new Gson();

    private record Wire(@SerializedName("u") String u, @SerializedName("t") String t, @SerializedName("k") String k) {
    }

    public static InviteToken parse(String code) throws PackException {
        String trimmed = code == null ? "" : code.strip();
        if (!trimmed.startsWith(PREFIX)) {
            throw new PackException(PackException.INVITE, "the invite code is wrong: copy it whole");
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(trimmed.substring(PREFIX.length()));
            Wire wire = GSON.fromJson(new String(raw, StandardCharsets.UTF_8), Wire.class);
            byte[] key = Base64.getDecoder().decode(wire.k());
            if (key.length != 32 || wire.t() == null || wire.t().isEmpty() || wire.u() == null
                    || !wire.u().startsWith("http")) {
                throw new PackException(PackException.INVITE, "the invite code is damaged");
            }
            return new InviteToken(wire.u(), wire.t(), key);
        } catch (IllegalArgumentException | NullPointerException | JsonSyntaxException e) {
            throw new PackException(PackException.INVITE, "the invite code is wrong: copy it whole", e);
        }
    }

    /** Builds a code (tests and tools). */
    public static String encode(String url, String token, byte[] publicKey) {
        String json = GSON.toJson(new Wire(url, token, Base64.getEncoder().encodeToString(publicKey)));
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
