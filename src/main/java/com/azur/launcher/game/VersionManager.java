package com.azur.launcher.game;

import com.google.gson.Gson;
import com.azur.launcher.model.DownloadInfo;
import com.azur.launcher.model.Library;
import com.azur.launcher.model.VersionManifest;
import com.azur.launcher.model.VersionMeta;
import com.azur.launcher.net.HttpUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class VersionManager {

    public static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private static final Gson GSON = new Gson();

    private final GamePaths paths;
    private final Consumer<String> logger;
    private final Map<String, VersionMeta> metaCache = new HashMap<>();
    private volatile VersionManifest manifest;

    public VersionManager(GamePaths paths, Consumer<String> logger) {
        this.paths = paths;
        this.logger = logger == null ? m -> {
        } : logger;
    }

    public VersionManifest getManifest(boolean forceDownload) throws IOException {
        if (!forceDownload && manifest != null) {
            return manifest;
        }
        Path file = paths.manifestFile();
        if (forceDownload || !Files.exists(file)) {
            logger.accept("Fetching version manifest: " + MANIFEST_URL);
            HttpUtils.download(MANIFEST_URL, file, null, 0);
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        VersionManifest m = GSON.fromJson(json, VersionManifest.class);
        if (m == null || m.getVersions() == null || m.getVersions().isEmpty()) {
            throw new IOException("Version manifest is empty or unreadable: " + file);
        }
        manifest = m;
        return m;
    }

    public VersionMeta getVersionMeta(String versionId, boolean forceDownload) throws IOException {
        if (!forceDownload) {
            VersionMeta cached = metaCache.get(versionId);
            if (cached != null) {
                return cached;
            }
        }

        List<VersionMeta> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = versionId;
        while (current != null && !current.isBlank()) {
            if (!visited.add(current)) {
                throw new IOException("Circular inheritsFrom chain: " + versionId);
            }
            VersionMeta meta = loadRaw(current, forceDownload);
            chain.add(meta);
            current = meta.getInheritsFrom();
        }

        VersionMeta merged = chain.get(chain.size() - 1);
        for (int i = chain.size() - 2; i >= 0; i--) {
            merged = merge(merged, chain.get(i));
        }
        merged.setId(versionId);
        metaCache.put(versionId, merged);
        return merged;
    }

    private VersionMeta loadRaw(String versionId, boolean forceDownload) throws IOException {
        Path jsonFile = paths.versionJson(versionId);
        if (forceDownload || !Files.exists(jsonFile)) {
            String url = findUrl(getManifest(false), versionId);
            if (url == null || url.isBlank()) {
                throw new IOException("Version is not in the manifest: " + versionId);
            }
            logger.accept("Fetching version JSON: " + versionId);
            HttpUtils.download(url, jsonFile, null, 0);
        }
        VersionMeta meta = GSON.fromJson(Files.readString(jsonFile, StandardCharsets.UTF_8), VersionMeta.class);
        if (meta == null) {
            throw new IOException("Cannot parse version JSON: " + jsonFile);
        }
        return meta;
    }

    private static String findUrl(VersionManifest manifest, String versionId) {
        if (manifest == null || manifest.getVersions() == null) {
            return null;
        }
        for (VersionManifest.VersionEntry entry : manifest.getVersions()) {
            if (versionId.equals(entry.getId())) {
                return entry.getUrl();
            }
        }
        return null;
    }

    private VersionMeta merge(VersionMeta parent, VersionMeta child) {
        if (parent == null) {
            return child;
        }
        if (child == null) {
            return parent;
        }
        VersionMeta r = new VersionMeta();
        r.setId(firstNonBlank(child.getId(), parent.getId()));
        r.setType(firstNonBlank(child.getType(), parent.getType()));
        r.setMainClass(firstNonBlank(child.getMainClass(), parent.getMainClass()));
        r.setAssets(firstNonBlank(child.getAssets(), parent.getAssets()));
        r.setJar(firstNonBlank(child.getJar(), parent.getJar()));
        r.setMinecraftArguments(firstNonBlank(child.getMinecraftArguments(), parent.getMinecraftArguments()));
        r.setAssetIndex(child.getAssetIndex() != null ? child.getAssetIndex() : parent.getAssetIndex());
        r.setArguments(child.getArguments() != null ? child.getArguments() : parent.getArguments());
        r.setJavaVersion(child.getJavaVersion() != null ? child.getJavaVersion() : parent.getJavaVersion());

        Map<String, DownloadInfo> downloads = new LinkedHashMap<>();
        if (parent.getDownloads() != null) {
            downloads.putAll(parent.getDownloads());
        }
        if (child.getDownloads() != null) {
            downloads.putAll(child.getDownloads());
        }
        r.setDownloads(downloads);

        Map<String, Library> libs = new LinkedHashMap<>();
        if (parent.getLibraries() != null) {
            for (Library lib : parent.getLibraries()) {
                libs.put(dedupKey(lib), lib);
            }
        }
        if (child.getLibraries() != null) {
            for (Library lib : child.getLibraries()) {
                libs.put(dedupKey(lib), lib);
            }
        }
        r.setLibraries(new ArrayList<>(libs.values()));
        return r;
    }

    private static String dedupKey(Library lib) {
        return lib.getGroupId() + ":" + lib.getArtifactId();
    }

    private static String firstNonBlank(String a, String b) {
        return (a == null || a.isBlank()) ? b : a;
    }
}
