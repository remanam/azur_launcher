package com.azur.launcher.profile;

import com.azur.launcher.servers.ServerEntry;

import java.util.List;

/**
 * Derives the pinned profile of a server from its signed list entry. Versions come from the entry; what the
 * list does not carry (JVM flags, RAM, mirror) comes from the embedded profile. The installer hash is taken from
 * the entry, or from the embedded profile when the entry names the very same NeoForge build; any other build
 * without a hash is refused, because an unchecked installer would be run.
 */
public final class ServerProfiles {

    private ServerProfiles() {
    }

    public static Profile derive(ServerEntry entry, Profile base) throws ProfileException {
        String neoforge = entry.neoforge().isBlank() ? base.neoforge() : entry.neoforge();
        String sha = entry.neoforgeSha256();
        if (sha.isBlank() && neoforge.equals(base.neoforge())) {
            sha = base.neoforgeInstallerSha256();
        }
        int java = base.java();
        if (!entry.java().isBlank()) {
            try {
                java = Integer.parseInt(entry.java().trim());
            } catch (NumberFormatException e) {
                throw new ProfileException("Server " + entry.id() + ": bad java version " + entry.java());
            }
        }
        Profile profile = new Profile(Profile.SCHEMA, entry.id(),
                entry.mcVersion().isBlank() ? base.minecraft() : entry.mcVersion(), neoforge, sha, java,
                List.copyOf(base.jvmArgs()), base.ramMinMb(), base.ramMaxMb(),
                entry.packChannel().isBlank() ? base.packChannel() : entry.packChannel(),
                entry.mirror() == null || entry.mirror().isBlank() ? base.mirrorBase() : entry.mirror());
        List<String> problems = ProfileLoader.validate(profile);
        if (!problems.isEmpty()) {
            throw new ProfileException("Server " + entry.id() + ": " + String.join("; ", problems));
        }
        return profile;
    }
}
