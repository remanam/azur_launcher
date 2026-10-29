package com.azur.launcher.servers;

/** One server of the signed list (see docs/SERVERS_JSON.md). */
public record ServerEntry(
        String id,
        String name,
        String address,
        int port,
        String mcVersion,
        String neoforge,
        String neoforgeSha256,
        String java,
        String packChannel,
        String packUrl,
        boolean inviteRequired,
        String motd) {

    public static final int DEFAULT_PORT = 25565;

    /** The address as players type it: host, or host:port when the port is not the default. */
    public String hostPort() {
        return port == DEFAULT_PORT ? address : address + ":" + port;
    }
}
