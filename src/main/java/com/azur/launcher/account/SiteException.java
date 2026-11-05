package com.azur.launcher.account;

/** A failed site call. {@link #network()} = no answer at all; otherwise the site's own error code and HTTP status. */
public final class SiteException extends Exception {

    private final int status;
    private final String code;

    public SiteException(int status, String code) {
        super("site " + status + " " + code);
        this.status = status;
        this.code = code == null ? "" : code;
    }

    public SiteException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.code = "";
    }

    public boolean network() {
        return status == 0;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
