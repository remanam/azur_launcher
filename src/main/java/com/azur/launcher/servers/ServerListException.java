package com.azur.launcher.servers;

/** The server list file is unusable; {@link #reason()} says why. */
public final class ServerListException extends Exception {

    public enum Reason { BAD_FORMAT, BAD_SIGNATURE, EXPIRED }

    private final Reason reason;

    public ServerListException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
