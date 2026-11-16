package com.azur.launcher.pack;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Journal of one sync run: files it added and files it replaced or removed (moved into {@code files/}). A failed run
 * restores them; {@link #rollbackLatest} restores the newest finished run. Only the last two runs are kept.
 */
public final class PackBackup {

    private static final int KEEP = 2;
    private static final Gson GSON = new Gson();

    private record Journal(String time, @SerializedName("pack_before") String packBefore,
            @SerializedName("owned_before") Map<String, String> ownedBefore, List<String> added, List<String> backed) {
    }

    private final Path dir;
    private final Path game;
    private final String time;
    private final String packBefore;
    private final Map<String, String> ownedBefore;
    private final List<String> added = new ArrayList<>();
    private final List<String> backed = new ArrayList<>();

    public PackBackup(Path home, Path game, String time, PackState before) {
        this.dir = root(home, game).resolve(time);
        this.game = game;
        this.time = time;
        this.packBefore = before.packVersion;
        this.ownedBefore = new HashMap<>(before.owned);
    }

    static Path root(Path home, Path game) {
        return home.resolve("backups").resolve(PackState.key(game));
    }

    public void noteAdded(String rel) {
        added.add(rel);
    }

    /** Moves the current file (if any) into the backup. */
    public void save(String rel) throws PackException {
        Path file = PackPaths.safeJoin(game, rel);
        if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            move(file, dir.resolve("files").resolve(rel));
        } catch (IOException e) {
            throw busy(e);
        }
        backed.add(rel);
    }

    /** Writes the journal (success) and drops the oldest backups. */
    public void commit(Path home) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("journal.json"),
                GSON.toJson(new Journal(time, packBefore, ownedBefore, added, backed)));
        prune(root(home, game));
    }

    /** Undoes this run and deletes its folder (failure path). */
    public void undo() throws IOException {
        try {
            restore(game, dir, added, backed);
        } finally {
            deleteTree(dir);
        }
    }

    /** Restores the newest backup of the instance and consumes it; returns the pack version that was restored. */
    public static String rollbackLatest(Path home, Path game) throws IOException {
        Path root = root(home, game);
        List<Path> runs = runs(root);
        if (runs.isEmpty()) {
            throw new PackException(PackException.APPLY, "there is no backup to roll back to");
        }
        Path latest = runs.get(runs.size() - 1);
        Journal j;
        try {
            j = GSON.fromJson(Files.readString(latest.resolve("journal.json")), Journal.class);
        } catch (IOException | RuntimeException e) {
            throw new PackException(PackException.APPLY, "the backup is damaged", e);
        }
        try {
            restore(game, latest, Objects.isNull(j.added()) ? List.of() : j.added(),
                    Objects.isNull(j.backed()) ? List.of() : j.backed());
        } catch (IOException e) {
            throw busy(e);
        }
        PackState state = PackState.load(home, game);
        state.packVersion = Objects.isNull(j.packBefore()) ? "" : j.packBefore();
        state.owned = Objects.isNull(j.ownedBefore()) ? new HashMap<>() : new HashMap<>(j.ownedBefore());
        state.save(home, game);
        deleteTree(latest);
        return state.packVersion;
    }

    private static void restore(Path game, Path dir, List<String> added, List<String> backed) throws IOException {
        IOException first = null;
        for (String rel : added) {
            try {
                Files.deleteIfExists(PackPaths.safeJoin(game, rel));
            } catch (IOException e) {
                first = Objects.isNull(first) ? e : first;
            }
        }
        for (String rel : backed) {
            try {
                move(dir.resolve("files").resolve(rel), PackPaths.safeJoin(game, rel));
            } catch (IOException e) {
                first = Objects.isNull(first) ? e : first;
            }
        }
        if (Objects.nonNull(first)) {
            throw first;
        }
    }

    private static void move(Path src, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Path> runs(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
    }

    private static void prune(Path root) throws IOException {
        List<Path> runs = runs(root);
        for (int i = 0; i < runs.size() - KEEP; i++) {
            deleteTree(runs.get(i));
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(x);
            }
        }
    }

    static PackException busy(IOException e) {
        return new PackException(PackException.APPLY, "could not change a file (close the game): " + e.getMessage(), e);
    }
}
