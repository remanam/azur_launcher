package com.azur.launcher.game;

import com.google.gson.Gson;
import com.azur.launcher.model.AssetIndex;
import com.azur.launcher.model.AssetObjects;
import com.azur.launcher.net.DownloadManager;
import com.azur.launcher.net.HttpUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public class AssetManager {

    public static final String RESOURCES_BASE = "https://resources.download.minecraft.net/";

    private static final Gson GSON = new Gson();

    private final GamePaths paths;
    private final Consumer<String> logger;

    public AssetManager(GamePaths paths, Consumer<String> logger) {
        this.paths = paths;
        this.logger = Objects.isNull(logger) ? m -> {
        } : logger;
    }

    public void ensureIndex(AssetIndex index) throws IOException {
        if (Objects.isNull(index) || Objects.isNull(index.getUrl())) {
            throw new IOException("Version JSON has no assetIndex");
        }
        Path file = paths.assetIndexFile(index.getId());
        logger.accept("Fetching asset index: " + index.getId());
        HttpUtils.download(index.getUrl(), file, index.getSha1(), index.getSize());
    }

    public AssetObjects readIndex(AssetIndex index) throws IOException {
        Path file = paths.assetIndexFile(index.getId());
        AssetObjects objects = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AssetObjects.class);
        if (Objects.isNull(objects) || Objects.isNull(objects.getObjects())) {
            throw new IOException("Cannot parse asset index: " + file);
        }
        return objects;
    }

    public List<DownloadManager.Task> buildDownloadTasks(AssetObjects index) {
        return buildDownloadTasks(index, false);
    }

    /** @param verifyExisting when true, present files are re-hashed (repair) instead of trusted */
    public List<DownloadManager.Task> buildDownloadTasks(AssetObjects index, boolean verifyExisting) {
        List<DownloadManager.Task> tasks = new ArrayList<>();
        int skipped = 0;
        for (Map.Entry<String, AssetObjects.ObjectEntry> entry : index.getObjects().entrySet()) {
            AssetObjects.ObjectEntry obj = entry.getValue();
            String hash = obj.getHash();
            if (Objects.isNull(hash) || hash.length() < 2) {
                continue;
            }
            Path target = paths.assetObject(hash);
            if (!verifyExisting && Files.exists(target)) {
                skipped++;
                continue;
            }
            String url = RESOURCES_BASE + hash.substring(0, 2) + "/" + hash;
            tasks.add(new DownloadManager.Task(url, target, hash, obj.getSize()));
        }
        if (skipped > 0) {
            logger.accept(skipped + " assets already present, " + tasks.size() + " to download");
        }
        return tasks;
    }
}
