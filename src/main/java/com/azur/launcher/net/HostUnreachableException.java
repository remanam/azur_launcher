package com.azur.launcher.net;

import com.azur.launcher.i18n.I18n;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Objects;
import javax.net.ssl.SSLException;

/** A host did not answer at all (timeout, refused, unknown, TLS cut): the message is for the player. */
public final class HostUnreachableException extends IOException {

    private final String host;

    private HostUnreachableException(String host, Throwable cause) {
        super(I18n.tr("net.unreachable", host), cause);
        this.host = host;
    }

    public String host() {
        return host;
    }

    /** Wraps {@code failure} when its cause chain is a connection-level failure, else returns it unchanged. */
    static IOException classify(IOException failure, String url) {
        for (Throwable t = failure; Objects.nonNull(t); t = t.getCause()) {
            if (t instanceof HostUnreachableException) {
                return (IOException) t;
            }
            if (t instanceof SocketTimeoutException || t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException || t instanceof SSLException) {
                return new HostUnreachableException(hostOf(url), failure);
            }
        }
        return failure;
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return Objects.isNull(host) ? url : host;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
