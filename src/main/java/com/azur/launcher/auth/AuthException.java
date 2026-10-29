package com.azur.launcher.auth;

public class AuthException extends Exception {

    private final String code;

    public AuthException(String code, String message) {
        super(message);
        this.code = code == null ? "" : code;
    }

    public AuthException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? "" : code;
    }

    public String getCode() {
        return code;
    }
}
