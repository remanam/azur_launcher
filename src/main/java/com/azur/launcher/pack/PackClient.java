package com.azur.launcher.pack;

import com.azur.launcher.net.Hashes;
import com.azur.launcher.net.HostPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The pack updater client (Java port of the Go {@code azur-updater}): checks the signed manifest, downloads missing or
 * changed files in parallel into a cache, installs them with a backup journal (any error rolls the run back), deletes
 * retired files, can verify offline and roll back. Blocking: call from a background thread.
 */
public final class PackClient {

    /** Version of this client against the manifest's {@code min_updater_version}. */
    public static final String VERSION = "0.1.0";
    private static final int DOWNLOAD_THREADS = 4;
    private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    /** Result of a check: the verified manifest and what an update would do. */
    public record Checked(PackServer server, PackManifest manifest, PackPlan plan, PackState state,
            PackServer.Status status) {
    }

    private final Path home;
    private final HostPolicy policy;

    /** @param home the client's own state folder (state, backups); never inside the game folder */
    public PackClient(Path home, HostPolicy policy) {
        this.home = home;
        this.policy = policy;
    }

    /** Contacts the server, verifies the manifest, plans the changes. Writes nothing. */
    public Checked check(String inviteCode, String serverUrl, Path game) throws IOException {
        PackServer server = new PackServer(InviteToken.parse(inviteCode), serverUrl, policy);
        PackServer.Status status = server.status();
        PackManifest manifest = server.manifest();
        if (compareVersion(VERSION, manifest.minUpdater()) < 0) {
            throw new PackException(PackException.OUTDATED, "this pack needs a newer launcher");
        }
        PackState state = PackState.load(home, game);
        return new Checked(server, manifest, PackPlan.build(manifest, game, state), state, status);
    }

    /** Brings {@code game} to the manifest; returns the installed pack version. */
    public String update(Checked checked, Path game, PackProgress progress) throws IOException {
        PackPlan plan = PackPlan.build(checked.manifest(), game, checked.state());
        Files.createDirectories(game);
        Path cache = game.resolve(".azur-cache");
        download(checked.server(), plan, cache, progress);
        String version = install(checked, plan, game, cache, progress);
        PackBackup.deleteTree(cache);
        return version;
    }

    /** Re-hashes the files this client installed; no network. */
    public String verify(Path game) throws IOException {
        PackState state = PackState.load(home, game);
        if (state.packVersion.isEmpty() && state.owned.isEmpty()) {
            throw new PackException(PackException.VERIFY, "the pack is not installed yet");
        }
        for (Map.Entry<String, String> e : new TreeMap<>(state.owned).entrySet()) {
            Path file = PackPaths.safeJoin(game, e.getKey());
            if (!Files.isRegularFile(file) || !e.getValue().equals(Hashes.sha256(file))) {
                throw new PackException(PackException.VERIFY, "changed or missing: " + e.getKey());
            }
        }
        return state.packVersion;
    }

    /** Restores the newest backup; returns the pack version that is installed again. */
    public String rollback(Path game) throws IOException {
        return PackBackup.rollbackLatest(home, game);
    }

    private void download(PackServer server, PackPlan plan, Path cache, PackProgress progress) throws IOException {
        Map<String, PackManifest.Entry> need = new TreeMap<>();
        long total = 0;
        for (PackPlan.Item item : plan.items()) {
            if (item.needsDownload() && need.putIfAbsent(item.file().sha256(), item.file()) == null) {
                total += item.size();
            }
        }
        if (need.isEmpty()) {
            return;
        }
        Throttle throttle = new Throttle(progress, total);
        AtomicLong done = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(DOWNLOAD_THREADS, r -> {
            Thread t = new Thread(r, "azur-pack-download");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (PackManifest.Entry f : need.values()) {
                jobs.add(pool.submit(() -> {
                    server.fetch(f.url(), f.sha256(), f.size(), cache.resolve(f.sha256()), n -> {
                        throttle.emit(f.path(), done.addAndGet(n));
                    });
                    return null;
                }));
            }
            for (Future<?> job : jobs) {
                job.get();
            }
            throttle.finish(done.get());
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io
                    : new PackException(PackException.NETWORK, "download failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PackException(PackException.NETWORK, "interrupted", e);
        } finally {
            pool.shutdownNow();
        }
    }

    private String install(Checked checked, PackPlan plan, Path game, Path cache, PackProgress progress)
            throws IOException {
        PackManifest manifest = checked.manifest();
        PackState state = checked.state();
        String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(STAMP);
        PackBackup backup = new PackBackup(home, game, stamp, state);
        Map<String, String> owned = new java.util.HashMap<>(state.owned);
        try {
            int n = 0;
            for (PackPlan.Item item : plan.items()) {
                progress.onProgress("install", item.path(), ++n, plan.items().size());
                apply(item, game, cache, backup, owned);
            }
            backup.commit(home);
        } catch (IOException | RuntimeException e) {
            try {
                backup.undo();
            } catch (IOException undoFailed) {
                e.addSuppressed(undoFailed);
            }
            throw e instanceof PackException pe ? pe : new PackException(PackException.APPLY, e.getMessage(), e);
        }
        state.packVersion = manifest.packVersion() == null ? "" : manifest.packVersion();
        state.owned = owned;
        state.save(home, game);
        return state.packVersion;
    }

    private static void apply(PackPlan.Item item, Path game, Path cache, PackBackup backup,
            Map<String, String> owned) throws IOException {
        Path dst = PackPaths.safeJoin(game, item.path());
        PackManifest.Entry f = item.file();
        switch (item.action()) {
            case ADD, UPDATE -> {
                if (item.action() == PackPlan.Action.UPDATE) {
                    backup.save(item.path());
                } else {
                    backup.noteAdded(item.path());
                }
                copyAtomic(cache.resolve(f.sha256()), dst);
                if (PackManifest.OVERWRITE.equals(f.policy())) {
                    owned.put(item.path(), f.sha256());
                }
            }
            case MERGE -> merge(item, dst, cache, backup);
            case REMOVE -> {
                backup.save(item.path());
                owned.remove(item.path());
            }
            case KEEP -> {
                if (f != null && PackManifest.OVERWRITE.equals(f.policy())) {
                    owned.put(item.path(), f.sha256());
                }
            }
            case SKIP -> { }
        }
    }

    private static void merge(PackPlan.Item item, Path dst, Path cache, PackBackup backup) throws IOException {
        String add = Files.readString(cache.resolve(item.file().sha256()), StandardCharsets.UTF_8);
        boolean exists = Files.exists(dst);
        String current = exists ? Files.readString(dst, StandardCharsets.UTF_8) : "";
        String merged = mergeLines(current, add);
        if (exists && merged.equals(current)) {
            return;
        }
        if (exists) {
            backup.save(item.path());
        } else {
            backup.noteAdded(item.path());
        }
        Files.createDirectories(dst.getParent());
        Files.writeString(dst, merged, StandardCharsets.UTF_8);
    }

    /** Appends lines of {@code add} that {@code current} lacks (order kept, no duplicates). */
    static String mergeLines(String current, String add) {
        java.util.Set<String> have = new java.util.HashSet<>();
        for (String l : current.split("\n", -1)) {
            have.add(l.stripTrailing());
        }
        StringBuilder out = new StringBuilder(current.replaceAll("\n+$", ""));
        for (String line : add.split("\n", -1)) {
            String l = line.stripTrailing();
            if (!l.isEmpty() && have.add(l)) {
                out.append('\n').append(l);
            }
        }
        return out.append('\n').toString();
    }

    private static void copyAtomic(Path src, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        Path tmp = dst.resolveSibling(dst.getFileName() + ".azur-tmp");
        try {
            Files.copy(src, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            PackServer.move(tmp, dst);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw PackBackup.busy(e);
        }
    }

    /** Dotted numeric versions; -1, 0, 1. A null or blank side counts as 0. */
    static int compareVersion(String a, String b) {
        String[] pa = a == null ? new String[0] : a.split("\\.");
        String[] pb = b == null ? new String[0] : b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? number(pa[i]) : 0;
            int y = i < pb.length ? number(pb[i]) : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Limits download progress events to one per 100 ms (the UI hops to its thread for each). */
    private static final class Throttle {
        private final PackProgress target;
        private final long total;
        private long last;

        Throttle(PackProgress target, long total) {
            this.target = target;
            this.total = total;
        }

        synchronized void emit(String file, long done) {
            long now = System.nanoTime();
            if (now - last >= PROGRESS_INTERVAL_NANOS) {
                last = now;
                target.onProgress("download", file, Math.min(done, total), total);
            }
        }

        synchronized void finish(long done) {
            target.onProgress("download", "", Math.min(done, total), total);
        }
    }
}
