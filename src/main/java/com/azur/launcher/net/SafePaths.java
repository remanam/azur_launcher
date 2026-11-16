package com.azur.launcher.net;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Path-traversal guard shared by every extraction and by paths taken from downloaded manifests. */
public final class SafePaths {

    private SafePaths() {
    }

    /** Resolves {@code relative} under {@code base}; throws when the result leaves {@code base}. */
    public static Path resolve(Path base, String relative) throws IOException {
        if (Objects.isNull(relative) || relative.isEmpty() || relative.indexOf('\0') >= 0) {
            throw new IOException("Invalid path: " + relative);
        }
        String clean = relative.replace('\\', '/');
        if (clean.startsWith("/") || clean.matches("^[A-Za-z]:.*")) {
            throw new IOException("Absolute path not allowed: " + relative);
        }
        Path root = base.toAbsolutePath().normalize();
        Path out = root.resolve(clean).normalize();
        if (!out.startsWith(root) || out.equals(root)) {
            throw new IOException("Path escapes " + root + ": " + relative);
        }
        return out;
    }
}
