package com.azur.launcher.ui;

import com.azur.launcher.game.PinService;
import com.azur.launcher.profile.Instance;
import com.azur.launcher.profile.InstanceStore;
import com.azur.launcher.profile.ServerProfiles;
import com.azur.launcher.servers.ServerEntry;
import com.azur.launcher.sync.PackSyncService;
import com.azur.launcher.sync.SyncProgress;
import com.azur.launcher.sync.UpdaterBinary;

import java.io.IOException;
import java.nio.file.Path;

/** Install and update of one server's game and pack (shared by Home and the Servers page). Blocking: call off the UI thread. */
final class PackOps {

    /** One pack operation result; {@code localCopy}: the update failed but the installed pack still verifies. */
    record Outcome(PackSyncService.Result result, boolean verifyOnly, boolean localCopy) {
    }

    private PackOps() {
    }

    /** Installs the pin (client, libraries, assets) and then the pack when an invite exists; {@code entry} null = default instance. */
    static Outcome download(AppContext ctx, ServerEntry entry, SyncProgress sync,
                            com.azur.launcher.net.DownloadManager.ProgressListener pin) {
        String invite = ctx.invite();
        try {
            InstanceStore store = new InstanceStore(ctx.dirs);
            Instance instance = entry == null ? store.loadDefault()
                    : store.ensure(ServerProfiles.derive(entry, InstanceStore.bundledProfile()));
            new PinService(ctx.dirs, ctx.gamePaths(), ctx::log).installIfNeeded(instance,
                    ctx.config.getDownloadThreads(), pin);
            if (entry == null || invite.isBlank()) {
                return new Outcome(new PackSyncService.Result(0, "", "", ""), false, false);
            }
            return update(newService(ctx), entry, instance.gameDir(), invite, sync);
        } catch (IOException e) {
            ctx.log("Download client: " + e.getMessage());
            return new Outcome(new PackSyncService.Result(-1, "download", e.getMessage(), ""), false, false);
        }
    }

    static PackSyncService newService(AppContext ctx) throws IOException {
        return new PackSyncService(UpdaterBinary.resolve(ctx.pack.getUpdaterPath(), ctx.dirs.binDir()),
                PackSyncService.systemStarter(ctx.dirs.updaterHome()));
    }

    /**
     * Updates the pack. When the pack server cannot be reached but the installed files still verify, the player keeps
     * using the local copy (restricted networks, outages) instead of being blocked.
     */
    static Outcome update(PackSyncService service, ServerEntry entry, Path instance, String invite,
                          SyncProgress progress) throws IOException {
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
