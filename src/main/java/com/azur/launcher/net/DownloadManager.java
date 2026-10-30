package com.azur.launcher.net;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Bounded parallel downloads; tasks with the same target are merged so two workers never share a temp file. */
public class DownloadManager {

    public static class Task {
        public final String url;
        public final Path target;
        public final String sha1;
        public final long size;

        public Task(String url, Path target, String sha1, long size) {
            this.url = url;
            this.target = target;
            this.sha1 = sha1;
            this.size = size;
        }
    }

    public interface ProgressListener {
        void onProgress(int done, int total, String current);

        ProgressListener NOOP = (done, total, current) -> {
        };
    }

    private static final int MAX_THREADS = 32;

    private final int threads;
    private final Consumer<String> logger;
    private final ProgressListener progress;
    private final Downloader downloader;

    public DownloadManager(int threads, Consumer<String> logger, ProgressListener progress) {
        this(threads, logger, progress, null);
    }

    public DownloadManager(int threads, Consumer<String> logger, ProgressListener progress, Downloader downloader) {
        this.threads = Math.min(MAX_THREADS, Math.max(1, threads));
        this.logger = logger == null ? msg -> {
        } : logger;
        this.progress = progress == null ? ProgressListener.NOOP : progress;
        this.downloader = downloader;
    }

    /** @return true when every task succeeded */
    public boolean downloadAll(List<Task> allTasks) {
        if (allTasks == null || allTasks.isEmpty()) {
            return true;
        }
        Map<Path, Task> unique = new LinkedHashMap<>();
        for (Task task : allTasks) {
            unique.putIfAbsent(task.target.toAbsolutePath().normalize(), task);
        }
        List<Task> tasks = new ArrayList<>(unique.values());
        Downloader engine = downloader != null ? downloader : HttpUtils.downloader();

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, tasks.size()));
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        for (Task task : tasks) {
            pool.submit(() -> {
                try {
                    engine.downloadFirst(Mirrors.candidates(task.url, HttpUtils.mirror()), task.target,
                            Checks.of(task.sha1, task.size));
                } catch (Exception e) {
                    failed.incrementAndGet();
                    logger.accept("Download failed: " + task.url + " -> " + e.getMessage());
                } finally {
                    progress.onProgress(done.incrementAndGet(), tasks.size(), task.target.getFileName().toString());
                }
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.HOURS)) {
                pool.shutdownNow();
                logger.accept("Download timed out, remaining tasks cancelled");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            return false;
        }
        return failed.get() == 0;
    }
}
