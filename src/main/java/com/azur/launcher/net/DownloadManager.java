package com.azur.launcher.net;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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

    private final int threads;
    private final Consumer<String> logger;
    private final ProgressListener progress;

    public DownloadManager(int threads, Consumer<String> logger, ProgressListener progress) {
        this.threads = Math.max(1, threads);
        this.logger = logger == null ? msg -> {
        } : logger;
        this.progress = progress == null ? ProgressListener.NOOP : progress;
    }

    public DownloadManager(Consumer<String> logger) {
        this(defaultThreads(), logger, ProgressListener.NOOP);
    }

    public static int defaultThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.min(32, Math.max(4, cores * 2));
    }

    public boolean downloadAll(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return true;
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, tasks.size()));
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(tasks.size());

        for (Task task : tasks) {
            futures.add(pool.submit(() -> {
                try {
                    HttpUtils.download(task.url, task.target, task.sha1, task.size);
                } catch (Exception e) {
                    failed.incrementAndGet();
                    logger.accept("Download failed: " + task.url + " -> " + e.getMessage());
                } finally {
                    progress.onProgress(done.incrementAndGet(), tasks.size(), task.target.getFileName().toString());
                }
            }));
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
