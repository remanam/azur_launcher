package com.azur.launcher.ui;

import com.azur.launcher.game.PinService;
import com.azur.launcher.net.DownloadManager;
import com.azur.launcher.net.HttpUtils;
import com.azur.launcher.profile.Instance;
import com.azur.launcher.profile.InstanceStore;
import com.azur.launcher.profile.ServerProfiles;
import com.azur.launcher.servers.ServerEntry;
import com.azur.launcher.sync.PackSyncService;
import com.azur.launcher.sync.SyncProgress;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Install and update of one server's game and pack (shared by Home and the Servers page). Blocking: call off the UI thread. */
final class PackOps {

    /** One pack operation result; {@code localCopy}: the update failed but the installed pack still verifies. */
    record Outcome(PackSyncService.Result result, boolean verifyOnly, boolean localCopy) {
    }

    private PackOps() {
    }

    /** Installs the pin and then the pack, reporting steps, bytes and speed into {@code progress}. */
    static Outcome download(AppContext ctx, ServerEntry entry, InstallProgress progress) {
        return run(ctx, entry,
                (phase, file, done, total) -> {
                    if ("download".equals(phase)) {
                        progress.bytes(InstallProgress.Step.PACK, done, total);
                    } else {
                        progress.files(InstallProgress.Step.PACK, done, total);
                    }
                },
                (done, total, file) -> progress.files(InstallProgress.Step.MINECRAFT, done, total),
                (done, total, file) -> {
                    progress.files(InstallProgress.Step.LIBRARIES, done, total);
                    if (done >= total) {
                        progress.step(InstallProgress.Step.NEOFORGE);
                    }
                },
                progress::step);
    }

    /** Installs the pin (client, libraries, assets) and then the pack when an invite exists; {@code entry} null = default instance. */
    static Outcome download(AppContext ctx, ServerEntry entry, SyncProgress sync,
                            com.azur.launcher.net.DownloadManager.ProgressListener pin) {
        return run(ctx, entry, sync, pin, null, null);
    }

    private static Outcome run(AppContext ctx, ServerEntry entry, SyncProgress sync,
                               DownloadManager.ProgressListener pin, DownloadManager.ProgressListener libraries,
                               Consumer<InstallProgress.Step> step) {
        Consumer<InstallProgress.Step> at = step == null ? s -> { } : step;
        String invite = ctx.invite();
        try {
            InstanceStore store = new InstanceStore(ctx.dirs);
            Instance instance = entry == null ? store.loadDefault()
                    : store.ensure(ServerProfiles.derive(entry, InstanceStore.bundledProfile()));
            HttpUtils.useMirror(instance.profile().mirrorBase());
            PinService pins = new PinService(ctx.dirs, ctx.gamePaths(), ctx::log).onInstallerLibraries(libraries);
            at.accept(InstallProgress.Step.NEOFORGE);
            pins.installIfNeeded(instance);
            at.accept(InstallProgress.Step.MINECRAFT);
            pins.installIfNeeded(instance, ctx.config.getDownloadThreads(), pin);
            if (entry == null || invite.isBlank()) {
                return new Outcome(new PackSyncService.Result(0, "", "", ""), false, false);
            }
            at.accept(InstallProgress.Step.CHECK);
            return update(PackSyncService.standard(ctx.dirs), entry, instance.gameDir(), invite, sync);
        } catch (IOException e) {
            ctx.log("Download client: " + e.getMessage());
            return new Outcome(new PackSyncService.Result(-1, "download", e.getMessage(), ""), false, false);
        }
    }

    static PackSyncService newService(AppContext ctx) {
        return PackSyncService.standard(ctx.dirs);
    }

    /**
     * Updates the pack. When the pack server cannot be reached but the installed files still verify, the player keeps
     * using the local copy (restricted networks, outages) instead of being blocked.
     */
    static Outcome update(PackSyncService service, ServerEntry entry, Path instance, String invite,
                          SyncProgress progress) {
        PackSyncService.Result result = service.sync(entry.packUrl(), instance, invite, progress);
        if (result.ok() || !"network".equals(result.code())) {
            return new Outcome(result, false, false);
        }
        PackSyncService.Result local = service.verify(instance);
        if (!local.ok()) {
            return new Outcome(result, false, false);
        }
        return new Outcome(new PackSyncService.Result(0, "", result.message(), local.packVersion()), false, true);
    }
}
