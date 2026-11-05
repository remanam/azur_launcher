package com.azur.launcher.keys;

/** A keyset that is refused; the reason is logged (never shown as a code to the player). */
public final class KeysetException extends Exception {

    public enum Reason { BAD_FORMAT, BAD_SIGNATURE, ROLLBACK, EXPIRED, NEEDS_EMERGENCY }

    private final Reason reason;

    public KeysetException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
