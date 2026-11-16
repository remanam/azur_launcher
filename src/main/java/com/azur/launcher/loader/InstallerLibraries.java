package com.azur.launcher.loader;

import com.azur.launcher.net.Checks;
import com.azur.launcher.net.DownloadManager;
import com.azur.launcher.net.HttpUtils;
import com.azur.launcher.net.SafePaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fetches the libraries a loader installer would download itself ({@code install_profile.json} and {@code version.json}
 * inside the installer jar), through the launcher's own downloader: proxy, own mirror first, hash check. The installer
 * then finds every file already in place and downloads nothing, so it works where its own hosts are blocked.
 */
final class InstallerLibraries {

    /** One library file to place under {@code libraries/}. */
    record Item(String path, String url, String sha1, long size) {
    }

    private InstallerLibraries() {
    }

    /** Parses both JSON files of the installer; entries without a download URL are bundled in the jar and skipped. */
    static List<Item> list(Path installerJar) throws IOException {
        Map<String, Item> byPath = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(installerJar.toFile())) {
            for (String name : new String[] {"install_profile.json", "version.json"}) {
                ZipEntry entry = zip.getEntry(name);
                if (Objects.isNull(entry)) {
                    continue;
                }
                try (Reader in = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                    collect(JsonParser.parseReader(in), byPath);
                }
            }
        }
        return new ArrayList<>(byPath.values());
    }

    static void collect(JsonElement root, Map<String, Item> out) {
        if (Objects.isNull(root) || !root.isJsonObject()) {
            return;
        }
        JsonArray libs = root.getAsJsonObject().getAsJsonArray("libraries");
        if (Objects.isNull(libs)) {
            return;
        }
        for (JsonElement lib : libs) {
            JsonObject downloads = lib.isJsonObject() ? lib.getAsJsonObject().getAsJsonObject("downloads") : null;
            JsonObject artifact = Objects.isNull(downloads) ? null : downloads.getAsJsonObject("artifact");
            if (Objects.isNull(artifact)) {
                continue;
            }
            String path = text(artifact, "path");
            String url = text(artifact, "url");
            if (path.isEmpty() || url.isEmpty()) {
                continue;
            }
            out.putIfAbsent(path, new Item(path, url, text(artifact, "sha1"),
                    artifact.has("size") ? artifact.get("size").getAsLong() : 0));
        }
    }

    /** Downloads every missing or damaged library into {@code <gameDir>/libraries}. */
    static void fetch(Path installerJar, Path gameDir, Consumer<String> log, DownloadManager.ProgressListener progress)
            throws IOException {
        List<Item> items = list(installerJar);
        Path libraries = gameDir.resolve("libraries");
        log.accept("Installer libraries: " + items.size());
        int done = 0;
        for (Item item : items) {
            Path target = SafePaths.resolve(libraries, item.path());
            HttpUtils.downloadMirrored(item.url(), target, Checks.of(item.sha1(), item.size()));
            progress.onProgress(++done, items.size(), item.path());
        }
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
