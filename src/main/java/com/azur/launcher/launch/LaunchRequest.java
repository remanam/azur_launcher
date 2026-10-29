package com.azur.launcher.launch;

import com.azur.launcher.servers.ServerEntry;

/**
 * What the "Play" button asks for: the server whose pack is installed. The implementation of {@link GameStarter}
 * picks the installed game version for it; accounts and per-server profiles plug in behind that interface.
 */
public record LaunchRequest(ServerEntry server) {
}
