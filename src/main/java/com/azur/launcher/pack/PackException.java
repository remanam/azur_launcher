package com.azur.launcher.pack;

import java.io.IOException;

/** A pack operation failed; {@code code} is the machine code the UI maps to a text ({@code sync.err.<code>}). */
public final class PackException extends IOException {

    public static final String INVITE = "invite";
    public static final String SIGNATURE = "signature";
    public static final String NETWORK = "network";
    public static final String OUTDATED = "outdated";
    public static final String APPLY = "apply";
    public static final String VERIFY = "verify";

    private final String code;

    public PackException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PackException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
