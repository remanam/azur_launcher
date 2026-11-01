package com.azur.launcher.game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** Append-only text log that rotates to {@code name.1}, {@code name.2}... once it passes {@code maxBytes}. Never throws. */
public final class RotatingLog {

    private final Path file;
    private final long maxBytes;
    private final int keep;

    public RotatingLog(Path file, long maxBytes, int keep) {
        this.file = file;
        this.maxBytes = maxBytes;
        this.keep = keep;
    }

    public synchronized void append(String line) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > maxBytes) {
                rotate();
            }
            String text = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS) + " " + line + System.lineSeparator();
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // logging must never break the launcher
        }
    }

    private void rotate() throws IOException {
        for (int i = keep; i >= 1; i--) {
            Path from = i == 1 ? file : sibling(i - 1);
            if (Files.exists(from)) {
                Files.move(from, sibling(i), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Path sibling(int n) {
        return file.resolveSibling(file.getFileName() + "." + n);
    }
}
