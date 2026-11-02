package com.azur.launcher.update;

/** The update manifest or jar did not pass a check; the message is for the log. */
public final class UpdateException extends Exception {

    public UpdateException(String message) {
        super(message);
    }
}
