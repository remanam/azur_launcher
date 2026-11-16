package com.azur.launcher.game;

import com.azur.launcher.model.DownloadInfo;
import com.azur.launcher.model.Library;
import com.azur.launcher.net.DownloadManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public class LibraryManager {

    private final GamePaths paths;
    private final Consumer<String> logger;

    public LibraryManager(GamePaths paths, Consumer<String> logger) {
        this.paths = paths;
        this.logger = Objects.isNull(logger) ? m -> {
        } : logger;
    }

    public List<Library> filterLibraries(List<Library> libraries) {
        List<Library> result = new ArrayList<>();
        if (Objects.isNull(libraries)) {
            return result;
        }
        for (Library lib : libraries) {
            if (PlatformRules.isAllowed(lib.getRules())) {
                result.add(lib);
            }
        }
        return result;
    }

    public List<DownloadManager.Task> buildDownloadTasks(List<Library> libraries) {
        List<DownloadManager.Task> tasks = new ArrayList<>();
        for (Library lib : filterLibraries(libraries)) {
            DownloadInfo artifact = Objects.isNull(lib.getDownloads()) ? null : lib.getDownloads().getArtifact();

            if (Objects.nonNull(artifact) && Objects.nonNull(artifact.getUrl())) {
                tasks.add(new DownloadManager.Task(artifact.getUrl(),
                        resolveArtifactPath(lib), artifact.getSha1(), artifact.getSize()));
            } else if (Objects.nonNull(lib.getUrl()) && !lib.getUrl().isBlank()) {
                String base = lib.getUrl().endsWith("/") ? lib.getUrl() : lib.getUrl() + "/";
                String url = base + lib.getDirectoryPath() + "/" + lib.getArtifactFileName();
                Path target = libPath(lib.getDirectoryPath() + "/" + lib.getArtifactFileName());
                tasks.add(new DownloadManager.Task(url, target, null, 0));
            }

            String classifier = PlatformRules.nativesClassifier(lib.getNatives());
            if (Objects.nonNull(classifier)) {
                DownloadInfo nativeInfo = (Objects.isNull(lib.getDownloads()) || Objects.isNull(lib.getDownloads().getClassifiers()))
                        ? null
                        : lib.getDownloads().getClassifiers().get(classifier);
                if (Objects.nonNull(nativeInfo) && Objects.nonNull(nativeInfo.getUrl())) {
                    tasks.add(new DownloadManager.Task(nativeInfo.getUrl(),
                            resolveClassifierPath(lib, classifier),
                            nativeInfo.getSha1(), nativeInfo.getSize()));
                }
            }
        }
        return tasks;
    }

    public List<Path> buildClasspath(List<Library> libraries) {
        List<Path> classpath = new ArrayList<>();
        for (Library lib : filterLibraries(libraries)) {
            DownloadInfo artifact = Objects.isNull(lib.getDownloads()) ? null : lib.getDownloads().getArtifact();
            if (Objects.isNull(artifact) && (Objects.isNull(lib.getUrl()) || lib.getUrl().isBlank())) {
                continue;
            }
            classpath.add(resolveArtifactPath(lib));
        }
        return classpath;
    }

    public List<NativeEntry> collectNatives(List<Library> libraries) {
        List<NativeEntry> entries = new ArrayList<>();
        for (Library lib : filterLibraries(libraries)) {
            String classifier = PlatformRules.nativesClassifier(lib.getNatives());
            if (Objects.isNull(classifier)) {
                continue;
            }
            List<String> exclude = Objects.isNull(lib.getExtract()) ? null : lib.getExtract().getExclude();
            entries.add(new NativeEntry(resolveClassifierPath(lib, classifier), exclude));
        }
        return entries;
    }

    public Path resolveArtifactPath(Library lib) {
        DownloadInfo artifact = Objects.isNull(lib.getDownloads()) ? null : lib.getDownloads().getArtifact();
        if (Objects.nonNull(artifact) && Objects.nonNull(artifact.getPath()) && !artifact.getPath().isBlank()) {
            return libPath(artifact.getPath());
        }
        return libPath(lib.getDirectoryPath() + "/" + lib.getArtifactFileName());
    }

    public Path resolveClassifierPath(Library lib, String classifier) {
        DownloadInfo info = (Objects.isNull(lib.getDownloads()) || Objects.isNull(lib.getDownloads().getClassifiers()))
                ? null
                : lib.getDownloads().getClassifiers().get(classifier);
        if (Objects.nonNull(info) && Objects.nonNull(info.getPath()) && !info.getPath().isBlank()) {
            return libPath(info.getPath());
        }
        String fileName = lib.getArtifactId() + "-" + lib.getVersion() + "-" + classifier + ".jar";
        return libPath(lib.getDirectoryPath() + "/" + fileName);
    }

    /** Manifest-supplied relative path, checked against traversal. */
    private Path libPath(String relative) {
        try {
            return paths.libraryFile(relative);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static class NativeEntry {
        public final Path zip;
        public final List<String> exclude;

        public NativeEntry(Path zip, List<String> exclude) {
            this.zip = zip;
            this.exclude = exclude;
        }
    }
}
