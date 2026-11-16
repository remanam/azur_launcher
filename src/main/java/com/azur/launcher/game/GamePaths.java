package com.azur.launcher.game;

import java.nio.file.Path;
import java.util.Objects;

public final class GamePaths {

    private static final java.util.regex.Pattern VERSION_ID = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}");
    private static final java.util.regex.Pattern HASH = java.util.regex.Pattern.compile("[0-9a-f]{40}");

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
        if (!VERSION_ID.matcher(versionId).matches()) {
            throw new IllegalArgumentException("Invalid version id: " + versionId);
        }
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

    /** Library path taken from a manifest; must stay inside the libraries directory. */
    public Path libraryFile(String relative) throws java.io.IOException {
        return com.azur.launcher.net.SafePaths.resolve(librariesDir(), relative);
    }

    public Path assetIndexFile(String indexId) {
        return indexesDir().resolve(indexId + ".json");
    }

    public Path assetObject(String hash) {
        if (!HASH.matcher(hash).matches()) {
            throw new IllegalArgumentException("Invalid asset hash: " + hash);
        }
        return objectsDir().resolve(hash.substring(0, 2)).resolve(hash);
    }

    /**
     * Working directory of a game version. Every version is isolated: mods, saves and configs live in
     * {@code versions/<id>}, so the pinned pack never mixes with another install.
     */
    public Path contentRoot(String versionId) {
        return Objects.isNull(versionId) || versionId.isBlank() ? root : versionDir(versionId);
    }
}
