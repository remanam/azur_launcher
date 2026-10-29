package com.azur.launcher.profile;

import java.util.List;

/**
 * Pinned versions and launch settings of one server instance ({@code profile.json}). The launcher refuses to start
 * the game when the installed versions differ from this pin. Validated by {@link ProfileLoader}.
 */
public record Profile(
        int schema,
        String serverId,
        String minecraft,
        String neoforge,
        String neoforgeInstallerSha256,
        int java,
        List<String> jvmArgs,
        int ramMinMb,
        int ramMaxMb,
        String packChannel,
        String mirrorBase) {

    public static final int SCHEMA = 1;

    /** Version id that the NeoForge installer creates under {@code versions/}. */
    public String neoforgeVersionId() {
        return "neoforge-" + neoforge;
    }
}
