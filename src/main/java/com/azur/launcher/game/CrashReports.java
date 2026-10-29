package com.azur.launcher.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/** Finds the crash report a game run produced. */
public final class CrashReports {

    private CrashReports() {
    }

    /** Newest {@code crash-*.txt} in {@code dir} modified at or after {@code sinceMillis}. */
    public static Optional<Path> newestSince(Path dir, long sinceMillis) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("crash-"))
                    .filter(p -> modified(p) >= sinceMillis)
                    .max((a, b) -> Long.compare(modified(a), modified(b)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
