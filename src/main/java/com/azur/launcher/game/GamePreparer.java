package com.azur.launcher.game;

import com.azur.launcher.i18n.I18n;
import com.azur.launcher.model.AssetObjects;
import com.azur.launcher.model.DownloadInfo;
import com.azur.launcher.model.VersionMeta;
import com.azur.launcher.net.DownloadManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Brings client jar, libraries, assets and natives to the state the manifests describe (all hashes checked)
 * and returns what the command builder needs. With {@code verifyExisting} every present file is re-hashed:
 * that is the "verify files" repair.
 */
public final class GamePreparer {

    /** Result of {@link #prepare}. */
    public record Prepared(Path clientJar, String classpath, Path nativesDir, String assetIndexName) {
    }

    private final GamePaths paths;
    private final Consumer<String> logger;
    private final DownloadManager.ProgressListener progress;
    private final int threads;

    public GamePreparer(GamePaths paths, Consumer<String> logger, DownloadManager.ProgressListener progress,
                        int threads) {
        this.paths = paths;
        this.logger = logger;
        this.progress = progress;
        this.threads = threads;
    }

    public Prepared prepare(String versionId, VersionMeta meta, boolean verifyExisting) throws IOException {
        List<DownloadManager.Task> tasks = new ArrayList<>();
        String jarId = (Objects.nonNull(meta.getJar()) && !meta.getJar().isBlank()) ? meta.getJar() : versionId;
        Path clientJar = paths.clientJar(jarId);
        DownloadInfo client = meta.getClientDownload();
        if (Objects.nonNull(client) && Objects.nonNull(client.getUrl()) && !client.getUrl().isBlank()) {
            tasks.add(new DownloadManager.Task(client.getUrl(), clientJar, client.getSha1(), client.getSize()));
        } else if (!Files.isRegularFile(clientJar)) {
            throw new IOException(I18n.tr("launch.no_client", clientJar));
        }

        LibraryManager libraryManager = new LibraryManager(paths, logger);
        tasks.addAll(libraryManager.buildDownloadTasks(meta.getLibraries()));

        AssetManager assetManager = new AssetManager(paths, logger);
        String assetIndexName = meta.getAssets();
        if (Objects.nonNull(meta.getAssetIndex())) {
            assetManager.ensureIndex(meta.getAssetIndex());
            AssetObjects assetIndex = assetManager.readIndex(meta.getAssetIndex());
            tasks.addAll(assetManager.buildDownloadTasks(assetIndex, verifyExisting));
            if (Objects.isNull(assetIndexName) || assetIndexName.isBlank()) {
                assetIndexName = meta.getAssetIndex().getId();
            }
        } else if (Objects.isNull(assetIndexName)) {
            assetIndexName = "legacy";
        }

        logger.accept(tasks.size() + " files to process (valid ones are skipped), " + threads + " threads");
        if (!new DownloadManager(threads, logger, progress).downloadAll(tasks)) {
            throw new IOException(I18n.tr("launch.download_failed"));
        }

        Path nativesDir = paths.nativesDir(versionId);
        Files.createDirectories(nativesDir);
        int extracted = 0;
        for (LibraryManager.NativeEntry entry : libraryManager.collectNatives(meta.getLibraries())) {
            extracted += NativeExtractor.extract(entry.zip, nativesDir, entry.exclude);
        }
        logger.accept("Natives extracted: " + extracted + " files -> " + nativesDir);

        List<Path> entries = new ArrayList<>(libraryManager.buildClasspath(meta.getLibraries()));
        entries.add(clientJar);
        String classpath = entries.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        return new Prepared(clientJar, classpath, nativesDir, assetIndexName);
    }
}
