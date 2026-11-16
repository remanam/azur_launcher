package com.azur.launcher.pack;

import com.azur.launcher.net.Hashes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** What the client installed into one instance: pack version and the files it owns (path to sha256). */
public final class PackState {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String instance = "";
    @SerializedName("pack_version")
    public String packVersion = "";
    public Map<String, String> owned = new HashMap<>();
    @SerializedName("skip_optional")
    public List<String> skipOptional = new ArrayList<>();

    /** Stable short key of an instance folder; names its state file and its backup folder. */
    public static String key(Path instance) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(instance.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return Hashes.toHex(h).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path file(Path home, Path instance) {
        return home.resolve("state").resolve(key(instance) + ".json");
    }

    public static PackState load(Path home, Path instance) {
        PackState state = new PackState();
        try {
            Path f = file(home, instance);
            if (Files.isRegularFile(f)) {
                PackState read = GSON.fromJson(Files.readString(f), PackState.class);
                if (Objects.nonNull(read)) {
                    state = read;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // an unreadable state means "nothing installed yet": the next sync reinstalls what is missing
        }
        state.instance = instance.toString();
        if (Objects.isNull(state.owned)) {
            state.owned = new HashMap<>();
        }
        if (Objects.isNull(state.skipOptional)) {
            state.skipOptional = new ArrayList<>();
        }
        if (Objects.isNull(state.packVersion)) {
            state.packVersion = "";
        }
        return state;
    }

    public void save(Path home, Path instance) throws IOException {
        Path f = file(home, instance);
        Files.createDirectories(f.getParent());
        Files.writeString(f, GSON.toJson(this));
    }
}
