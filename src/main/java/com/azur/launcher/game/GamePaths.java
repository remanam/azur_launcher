package com.azur.launcher.game;

import java.nio.file.Path;

public final class GamePaths {

    public static final String MANIFEST_FILE = "version_manifest_v2.json";

    private final Path root;

    public GamePaths(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path versionsDir() {
        return root.resolve("versions");
    }

    public Path librariesDir() {
        return root.resolve("libraries");
    }

    public Path assetsDir() {
        return root.resolve("assets");
    }

    public Path indexesDir() {
        return assetsDir().resolve("indexes");
    }

    public Path objectsDir() {
        return assetsDir().resolve("objects");
    }

    public Path manifestFile() {
        return versionsDir().resolve(MANIFEST_FILE);
    }

    public Path versionDir(String versionId) {
        return versionsDir().resolve(versionId);
    }

    public Path versionJson(String versionId) {
        return versionDir(versionId).resolve(versionId + ".json");
    }

    public Path clientJar(String versionId) {
        return versionDir(versionId).resolve(versionId + ".jar");
    }

    public Path nativesDir(String versionId) {
        return versionDir(versionId).resolve("natives");
    }

    public Path assetIndexFile(String indexId) {
        return indexesDir().resolve(indexId + ".json");
    }

    public Path assetObject(String hash) {
        return objectsDir().resolve(hash.substring(0, 2)).resolve(hash);
    }

    /**
     * Working directory of a game version. Every version is isolated: mods, saves and configs live in
     * {@code versions/<id>}, so the pinned pack never mixes with another install.
     */
    public Path contentRoot(String versionId) {
        return versionId == null || versionId.isBlank() ? root : versionDir(versionId);
    }
}
