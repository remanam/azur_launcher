package com.azur.launcher.servers;

import java.util.List;

/** Which server Home shows: the remembered one, else the default {@code isles}, else the first. A selector exists only for 2+ servers. */
public final class ServerChoice {

    public static final String DEFAULT_ID = "isles";

    private ServerChoice() {
    }

    public static ServerEntry pick(List<ServerEntry> servers, String lastServerId) {
        if (servers.isEmpty()) {
            return null;
        }
        for (String wanted : new String[] {lastServerId, DEFAULT_ID}) {
            for (ServerEntry e : servers) {
                if (wanted != null && !wanted.isEmpty() && e.id().equals(wanted)) {
                    return e;
                }
            }
        }
        return servers.get(0);
    }

    public static boolean selectorVisible(List<ServerEntry> servers) {
        return servers.size() > 1;
    }
}
