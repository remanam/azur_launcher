package com.azur.launcher.ui;

import com.azur.launcher.game.PinService;
import com.azur.launcher.profile.Instance;

/** What Home needs to know about the pack; the pack updater plugs in its own source later. */
interface PackState {

    boolean installed(Instance instance);

    /** A newer pack version exists; unknown means false. */
    boolean updateAvailable(Instance instance);

    /** Current source: the pinned game install on disk, no update information yet. */
    static PackState local(AppContext ctx) {
        return new PackState() {
            @Override
            public boolean installed(Instance instance) {
                return new PinService(ctx.dirs, ctx.gamePaths(), ctx::log).check(instance).isEmpty();
            }

            @Override
            public boolean updateAvailable(Instance instance) {
                return false;
            }
        };
    }
}
