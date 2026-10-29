package com.azur.launcher.servers;

import java.time.Instant;
import java.util.List;

/** A verified server list. */
public record ServerList(int schema, Instant validUntil, List<ServerEntry> servers) {
}
