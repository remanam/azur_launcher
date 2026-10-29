package com.azur.launcher.profile;

import java.io.IOException;

public class ProfileException extends IOException {
    public ProfileException(String message) {
        super(message);
    }

    public ProfileException(String message, Throwable cause) {
        super(message, cause);
    }
}
