package com.azur.launcher.servers;

import com.azur.launcher.config.Branding;
import com.azur.launcher.pack.PackSource;

import java.util.List;

/**
 * The default server baked into the jar, defined by the optional build-time {@code branding.properties}
 * (keys {@code builtin.id, .name, .address, .port, .mc, .neoforge, .java, .channel, .pack_url, .pack_key, .motd,
 * .mirror}; see docs/BRANDING.md). Without branding there is no built-in server and the list is empty. Used only
 * when the signed list cannot be loaded and there is no cached copy. The pack manifest is still checked against its
 * own signature, so a wrong entry cannot install foreign files.
 */
public final class BuiltinServers {

    private static final ServerEntry DEFAULT = build();

    private BuiltinServers() {
    }

    private static ServerEntry build() {
        String address = Branding.get("builtin.address", "");
        if (address.isEmpty()) {
            return null;
        }
        int port = ServerEntry.DEFAULT_PORT;
        try {
            port = Integer.parseInt(Branding.get("builtin.port", String.valueOf(port)));
        } catch (NumberFormatException ignored) {
            // default port
        }
        return new ServerEntry(Branding.get("builtin.id", "default"), Branding.get("builtin.name", address), address,
                port, Branding.get("builtin.mc", "1.21.1"), Branding.get("builtin.neoforge", ""), "",
                Branding.get("builtin.java", "21"), Branding.get("builtin.channel", "stable"),
                new PackSource(Branding.get("builtin.pack_url", ""), Branding.get("builtin.pack_key", "")),
                Branding.get("builtin.motd", ""), Branding.get("builtin.mirror", ""));
    }

    /** The branded default server, or {@code null} in a generic build. */
    public static ServerEntry isles() {
        return DEFAULT;
    }

    public static List<ServerEntry> all() {
        return DEFAULT == null ? List.of() : List.of(DEFAULT);
    }
}
