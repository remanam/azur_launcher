package com.azur.launcher.ui;

import com.azur.launcher.game.PinService;
import com.azur.launcher.profile.Instance;
import com.azur.launcher.servers.ServerEntry;
import com.azur.launcher.sync.PackSyncService;

/**
 * What Home needs to know about the pack: installed (game pin on disk plus an installed pack version) and whether the
 * server announces a newer one. The server answer comes from a cheap {@code /status} request, cached for
 * {@link #TTL_MS} (a failure for {@link #RETRY_MS}); blocking, call off the UI thread.
 */
final class PackState {

    static final long TTL_MS = 5 * 60_000L;
    static final long RETRY_MS = 30_000L;

    /** One reading; {@code banner} is the maintenance text or empty. */
    record Snapshot(boolean installed, boolean updateAvailable, String banner) {
    }

    private final AppContext ctx;
    private final PackSyncService service;
    private PackSyncService.Remote remote = new PackSyncService.Remote(false, "", "");
    private String remoteKey = "";
    private long remoteAt;

    private PackState(AppContext ctx, PackSyncService service) {
        this.ctx = ctx;
        this.service = service;
    }

    static PackState live(AppContext ctx) {
        return new PackState(ctx, PackSyncService.standard(ctx.dirs));
    }

    /** @param server the selected server or null (default instance: no pack to track) */
    synchronized Snapshot read(Instance instance, ServerEntry server, boolean force) {
        boolean gameInstalled = new PinService(ctx.dirs, ctx.gamePaths(), ctx::log).check(instance).isEmpty();
        String invite = ctx.invite();
        boolean packExpected = server != null && !invite.isBlank();
        String local = packExpected ? service.installedVersion(instance.gameDir()) : "";
        boolean installed = HomeState.installed(gameInstalled, packExpected, local);
        if (!packExpected) {
            return new Snapshot(installed, false, "");
        }
        PackSyncService.Remote now = remote(server.packUrl(), invite, force);
        return new Snapshot(installed, installed && HomeState.updateAvailable(local, now.version()), now.banner());
    }

    private PackSyncService.Remote remote(String url, String invite, boolean force) {
        String key = url + '\n' + invite;
        long age = System.currentTimeMillis() - remoteAt;
        boolean fresh = key.equals(remoteKey) && age < (remote.reachable() ? TTL_MS : RETRY_MS);
        if (force || !fresh) {
            remote = service.probe(url, invite);
            remoteKey = key;
            remoteAt = System.currentTimeMillis();
        }
        return remote;
    }

    /** Forget the cached server answer (after an install the next reading asks again). */
    synchronized void invalidate() {
        remoteKey = "";
    }
}
