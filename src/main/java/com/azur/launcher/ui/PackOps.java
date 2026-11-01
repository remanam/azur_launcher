package com.azur.launcher.ui;

import com.azur.launcher.game.PinService;
import com.azur.launcher.net.HttpUtils;
import com.azur.launcher.profile.Instance;
import com.azur.launcher.servers.ServerEntry;
import com.azur.launcher.sync.PackSyncService;
import com.azur.launcher.sync.SyncProgress;

import java.io.IOException;
import java.nio.file.Path;

/** Game and pack install steps of one server's game and pack (shared by Home and the Servers page). Blocking: call off the UI thread. */
final class PackOps {

    /** One pack operation result; {@code localCopy}: the update failed but the installed pack still verifies. */
    record Outcome(PackSyncService.Result result, boolean verifyOnly, boolean localCopy) {
    }

    private PackOps() {
    }

    /** Installs the game pin (loader, client, libraries, assets) with step and byte progress. */
    static void installGame(AppContext ctx, Instance instance, InstallProgress progress) throws IOException {
        HttpUtils.useMirror(instance.profile().mirrorBase());
        PinService pins = new PinService(ctx.dirs, ctx.gamePaths(), ctx::log).onInstallerLibraries(
                (done, total, file) -> {
                    progress.files(InstallProgress.Step.LIBRARIES, done, total);
                    if (done >= total) {
                        progress.step(InstallProgress.Step.NEOFORGE);
                    }
                });
        progress.step(InstallProgress.Step.NEOFORGE);
        pins.installIfNeeded(instance);
        progress.step(InstallProgress.Step.MINECRAFT);
        pins.installIfNeeded(instance, ctx.config.getDownloadThreads(),
                (done, total, file) -> progress.files(InstallProgress.Step.MINECRAFT, done, total));
    }

    /** Downloads only the differing pack files; a local copy that still verifies counts as success. */
    static Outcome installPack(AppContext ctx, ServerEntry entry, Instance instance, InstallProgress progress) {
        progress.step(InstallProgress.Step.CHECK);
        return update(PackSyncService.standard(ctx.dirs), entry, instance.gameDir(),
                (phase, file, done, total) -> {
                    if ("download".equals(phase)) {
                        progress.bytes(InstallProgress.Step.PACK, done, total);
                    } else {
                        progress.files(InstallProgress.Step.PACK, done, total);
                    }
                });
    }

    static PackSyncService newService(AppContext ctx) {
        return PackSyncService.standard(ctx.dirs);
    }

    /**
     * Updates the pack. When the pack server cannot be reached but the installed files still verify, the player keeps
     * using the local copy (restricted networks, outages) instead of being blocked.
     */
    static Outcome update(PackSyncService service, ServerEntry entry, Path instance,
                          SyncProgress progress) {
        PackSyncService.Result result = service.sync(entry.pack(), instance, progress);
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
