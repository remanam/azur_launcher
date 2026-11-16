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
import java.util.Objects;
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
        this.logger = Objects.isNull(logger) ? m -> {
        } : logger;
    }

    public VersionManifest getManifest(boolean forceDownload) throws IOException {
        if (!forceDownload && Objects.nonNull(manifest)) {
            return manifest;
        }
        Path file = paths.manifestFile();
        if (forceDownload || !Files.exists(file)) {
            logger.accept("Fetching version manifest: " + MANIFEST_URL);
            HttpUtils.download(MANIFEST_URL, file, null, 0);
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        VersionManifest m = GSON.fromJson(json, VersionManifest.class);
        if (Objects.isNull(m) || Objects.isNull(m.getVersions()) || m.getVersions().isEmpty()) {
            throw new IOException("Version manifest is empty or unreadable: " + file);
        }
        manifest = m;
        return m;
    }

    public VersionMeta getVersionMeta(String versionId, boolean forceDownload) throws IOException {
        if (!forceDownload) {
            VersionMeta cached = metaCache.get(versionId);
            if (Objects.nonNull(cached)) {
                return cached;
            }
        }

        List<VersionMeta> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = versionId;
        while (Objects.nonNull(current) && !current.isBlank()) {
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
        boolean missing = !Files.exists(jsonFile);
        if (forceDownload || missing) {
            VersionManifest.VersionEntry entry = findEntry(getManifest(false), versionId);
            if (Objects.nonNull(entry) && Objects.nonNull(entry.getUrl()) && !entry.getUrl().isBlank()) {
                logger.accept("Fetching version JSON: " + versionId);
                HttpUtils.download(entry.getUrl(), jsonFile, entry.getSha1(), 0);
            } else if (missing) {
                throw new IOException("Version is not in the manifest: " + versionId);
            }
        }
        VersionMeta meta = GSON.fromJson(Files.readString(jsonFile, StandardCharsets.UTF_8), VersionMeta.class);
        if (Objects.isNull(meta)) {
            throw new IOException("Cannot parse version JSON: " + jsonFile);
        }
        return meta;
    }

    private static VersionManifest.VersionEntry findEntry(VersionManifest manifest, String versionId) {
        if (Objects.isNull(manifest) || Objects.isNull(manifest.getVersions())) {
            return null;
        }
        for (VersionManifest.VersionEntry entry : manifest.getVersions()) {
            if (versionId.equals(entry.getId())) {
                return entry;
            }
        }
        return null;
    }

    private VersionMeta merge(VersionMeta parent, VersionMeta child) {
        if (Objects.isNull(parent)) {
            return child;
        }
        if (Objects.isNull(child)) {
            return parent;
        }
        VersionMeta r = new VersionMeta();
        r.setId(firstNonBlank(child.getId(), parent.getId()));
        r.setType(firstNonBlank(child.getType(), parent.getType()));
        r.setMainClass(firstNonBlank(child.getMainClass(), parent.getMainClass()));
        r.setAssets(firstNonBlank(child.getAssets(), parent.getAssets()));
        r.setJar(firstNonBlank(child.getJar(), parent.getJar()));
        r.setMinecraftArguments(firstNonBlank(child.getMinecraftArguments(), parent.getMinecraftArguments()));
        r.setAssetIndex(Objects.nonNull(child.getAssetIndex()) ? child.getAssetIndex() : parent.getAssetIndex());
        r.setArguments(com.azur.launcher.model.Arguments.merge(parent.getArguments(), child.getArguments()));
        r.setJavaVersion(Objects.nonNull(child.getJavaVersion()) ? child.getJavaVersion() : parent.getJavaVersion());

        Map<String, DownloadInfo> downloads = new LinkedHashMap<>();
        if (Objects.nonNull(parent.getDownloads())) {
            downloads.putAll(parent.getDownloads());
        }
        if (Objects.nonNull(child.getDownloads())) {
            downloads.putAll(child.getDownloads());
        }
        r.setDownloads(downloads);

        Map<String, Library> libs = new LinkedHashMap<>();
        if (Objects.nonNull(parent.getLibraries())) {
            for (Library lib : parent.getLibraries()) {
                libs.put(dedupKey(lib), lib);
            }
        }
        if (Objects.nonNull(child.getLibraries())) {
            for (Library lib : child.getLibraries()) {
                libs.put(dedupKey(lib), lib);
            }
        }
        r.setLibraries(new ArrayList<>(libs.values()));
        return r;
    }

    /** Same library = group, artifact and classifier; version is not part of it (the child's version wins). */
    static String dedupKey(Library lib) {
        String[] coords = Objects.isNull(lib.getName()) ? new String[0] : lib.getName().split(":");
        return lib.getGroupId() + ":" + lib.getArtifactId() + (coords.length > 3 ? ":" + coords[3] : "");
    }

    private static String firstNonBlank(String a, String b) {
        return (Objects.isNull(a) || a.isBlank()) ? b : a;
    }
}
