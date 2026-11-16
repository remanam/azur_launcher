package com.azur.launcher.telemetry;

import com.azur.launcher.account.SiteException;
import com.azur.launcher.net.HostUnreachableException;
import com.azur.launcher.pack.PackException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.Objects;

/** Maps a failure to one word of the fixed list the site accepts. The message text is looked at here and never sent. */
public final class ErrorCode {

    public static final String NETWORK = "network";
    public static final String SIGNATURE = "signature";
    public static final String JAVA = "java";
    public static final String MOD = "mod";
    public static final String DISK = "disk";
    public static final String AUTH = "auth";
    public static final String OTHER = "other";

    private ErrorCode() {
    }

    public static String classify(Throwable failure) {
        for (Throwable t = failure; Objects.nonNull(t); t = t.getCause()) {
            if (t instanceof PackException p) {
                return switch (p.code()) {
                    case PackException.NETWORK -> NETWORK;
                    case PackException.SIGNATURE -> SIGNATURE;
                    default -> MOD;
                };
            }
            if (t instanceof SiteException) {
                return AUTH;
            }
            if (t instanceof HostUnreachableException || t instanceof UnknownHostException || t instanceof ConnectException
                    || t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return NETWORK;
            }
            String message = Objects.isNull(t.getMessage()) ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("no space left") || message.contains("not enough space")) {
                return DISK;
            }
            if (t instanceof IOException && (message.contains("hash") || message.contains("sha-1") || message.contains("sha1")
                    || message.contains("checksum"))) {
                return SIGNATURE;
            }
            if (message.contains("java") && (message.contains("not found") || message.contains("version") || message.contains("runtime"))) {
                return JAVA;
            }
        }
        return OTHER;
    }
}
