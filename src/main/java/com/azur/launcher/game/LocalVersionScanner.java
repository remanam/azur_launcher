package com.azur.launcher.game;

import com.azur.launcher.model.VersionMeta;
import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Lists installed versions from disk; works without network. */
public final class LocalVersionScanner {

    private static final Gson GSON = new Gson();

    private LocalVersionScanner() {
    }

    public static List<LocalVersion> scan(Path gameDir) {
        List<LocalVersion> result = new ArrayList<>();
        if (gameDir == null) {
            return result;
        }
        Path versionsDir = gameDir.resolve("versions");
        if (!Files.isDirectory(versionsDir)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(versionsDir)) {
            List<Path> dirs = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
            for (Path dir : dirs) {
                result.add(read(dir));
            }
        } catch (IOException ignored) {
            // an unreadable directory returns what was scanned so far
        }
        return result;
    }

    static LocalVersion read(Path dir) {
        String id = dir.getFileName().toString();
        Path json = dir.resolve(id + ".json");
        boolean valid = false;
        String type = null;
        String inheritsFrom = null;
        if (Files.isRegularFile(json)) {
            try {
                VersionMeta meta = GSON.fromJson(Files.readString(json, StandardCharsets.UTF_8), VersionMeta.class);
                if (meta != null) {
                    valid = true;
                    type = meta.getType();
                    inheritsFrom = meta.getInheritsFrom();
                }
            } catch (IOException | RuntimeException ignored) {
                // stays invalid
            }
        }
        return new LocalVersion(id, dir, Files.isRegularFile(dir.resolve(id + ".jar")), valid, type, inheritsFrom);
    }
}
