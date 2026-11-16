package com.azur.launcher.net;

import java.util.Objects;

/** Expected properties of a download; null/blank/non-positive values mean "not checked". */
public record Checks(String sha1, String sha256, long size) {

    public static final Checks NONE = new Checks(null, null, 0);

    public static Checks of(String sha1, long size) {
        return new Checks(sha1, null, size);
    }

    boolean has(String value) {
        return Objects.nonNull(value) && !value.isBlank();
    }
}
