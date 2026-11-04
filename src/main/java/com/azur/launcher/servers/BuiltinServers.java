package com.azur.launcher.servers;

import com.azur.launcher.pack.PackSource;

import java.util.List;

/**
 * The default server baked into the jar. Used only when the signed list cannot be loaded and there is no cached copy,
 * so a player still gets the Play button. Everything here is public data (the pack key is the public half). The
 * pack manifest is still checked against its own signature, so a wrong entry cannot install foreign files.
 */
public final class BuiltinServers {

    private static final ServerEntry ISLES = new ServerEntry("isles", "Example server", "play.example.test",
            ServerEntry.DEFAULT_PORT, "1.21.1", "21.1.264", "", "21", "stable",
            new PackSource("https://example.test/pack", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
            "Поднимай парус — приключения ждут на воде", "https://example.test/mirror");

    private BuiltinServers() {
    }

    public static ServerEntry isles() {
        return ISLES;
    }

    public static List<ServerEntry> all() {
        return List.of(ISLES);
    }
}
