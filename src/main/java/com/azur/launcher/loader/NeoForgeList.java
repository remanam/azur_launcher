package com.azur.launcher.loader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** The list of NeoForge builds: fetched with a fallback to the last good copy on disk (bounded). */
public final class NeoForgeList {

    static final int MAX_LINES = 3000;
    private static final long MAX_BYTES = 128 * 1024;

    /** Where the builds come from; the network in production, a stub in tests. */
    @FunctionalInterface
    public interface Source {
        List<String> fetch() throws IOException;
    }

    /** {@code error} is null when the list is fresh; otherwise {@code versions} is the cached copy (maybe empty). */
    public record Result(List<String> versions, String error) {

        public boolean fresh() {
            return error == null;
        }
    }

    private final Path cacheFile;

    public NeoForgeList(Path cacheFile) {
        this.cacheFile = cacheFile;
    }

    public Result load(Source source) {
        try {
            List<String> fresh = clean(source.fetch());
            if (fresh.isEmpty()) {
                throw new IOException("empty list");
            }
            save(fresh);
            return new Result(fresh, null);
        } catch (IOException | RuntimeException e) {
            String reason = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName()
                    : e.getMessage().strip();
            return new Result(cached(), reason.length() > 120 ? reason.substring(0, 120) : reason);
        }
    }

    /** Last good list, or empty when there is none or the file is unusable. */
    public List<String> cached() {
        try {
            if (!Files.isRegularFile(cacheFile) || Files.size(cacheFile) > MAX_BYTES) {
                return List.of();
            }
            return clean(Files.readAllLines(cacheFile, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private void save(List<String> versions) throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".part");
        Files.write(tmp, versions, StandardCharsets.UTF_8);
        Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Keeps valid, unique versions only, at most {@link #MAX_LINES}. */
    static List<String> clean(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String line : raw) {
            String v = line.trim();
            if (InstallEntries.isValidNeoForge(v) && !out.contains(v)) {
                out.add(v);
                if (out.size() >= MAX_LINES) {
                    break;
                }
            }
        }
        return out;
    }
}
