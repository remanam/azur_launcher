package com.azur.launcher.account;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Gives the game its proof of identity before it starts: a one-time ticket file (60 s) and the device key file. Only the paths
 * go on the command line (-Dazur.ticketFile, -Dazur.deviceKeyFile), never a secret. The game reads and deletes both at once
 * (azur_core TicketFile / DeviceKeyFile); the launcher removes leftovers before the next start and after the game exits.
 * The launcher is not needed while the game runs: nothing here is a long-lived service.
 */
public final class LoginHandoff {

    public static final String TICKET_FILE = ".azur_ticket";
    public static final String KEY_FILE = ".azur_devicekey";
    public static final String TICKET_PROPERTY = "azur.ticketFile";
    public static final String KEY_PROPERTY = "azur.deviceKeyFile";

    /** JVM arguments to add; {@code notice} is a log line about what could not be done (never a secret). */
    public record Prepared(List<String> jvmArgs, String notice) {
    }

    private final SiteAuth auth;
    private final String server;

    public LoginHandoff(SiteAuth auth, String server) {
        this.auth = auth;
        this.server = server;
    }

    /**
     * @param shareKey false = the key stays in the launcher and only the ticket is handed over
     * @throws SiteException the site refused the device (revoked, banned, no nick): the launch must stop
     */
    public Prepared prepare(Path gameDir, boolean shareKey) throws SiteException, IOException {
        cleanup(gameDir);
        Optional<SiteSession> session = auth.session();
        if (session.isEmpty()) {
            return new Prepared(List.of(), "not signed in: the game asks for a code");
        }
        List<String> args = new ArrayList<>();
        StringBuilder notice = new StringBuilder();
        try {
            SecretFile.write(gameDir.resolve(TICKET_FILE), auth.ticket(server));
            args.add("-D" + TICKET_PROPERTY + "=" + gameDir.resolve(TICKET_FILE));
        } catch (SiteException e) {
            if (!e.network() && !"rate_limited".equals(e.code())) {
                throw e; // revoked, banned, no nick: the player must see it, the game would be refused anyway
            }
            notice.append("no ticket (").append(e.network() ? "network" : e.code()).append(")");
        }
        if (shareKey) {
            SiteSession s = auth.session().orElse(session.get()); // after a rotation the key is a new one
            SecretFile.write(gameDir.resolve(KEY_FILE), s.deviceId() + "\n" + s.seed() + "\n");
            args.add("-D" + KEY_PROPERTY + "=" + gameDir.resolve(KEY_FILE));
        }
        return new Prepared(args, notice.toString());
    }

    /** Deletes the files the game did not take (crash before the login, ticket not used). */
    public static void cleanup(Path gameDir) {
        for (String name : List.of(TICKET_FILE, KEY_FILE)) {
            try {
                Files.deleteIfExists(gameDir.resolve(name));
            } catch (IOException ignored) {
                // best effort: the ticket expires in 60 s on the site anyway
            }
        }
    }
}
