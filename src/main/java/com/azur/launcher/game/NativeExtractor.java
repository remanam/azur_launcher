package com.azur.launcher.game;

import com.azur.launcher.net.SafePaths;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class NativeExtractor {

    private NativeExtractor() {
    }

    public static int extract(Path zipFile, Path destDir, List<String> excludes) throws IOException {
        if (zipFile == null || !Files.isRegularFile(zipFile)) {
            return 0;
        }
        Files.createDirectories(destDir);
        Path dest = destDir.toAbsolutePath().normalize();
        int count = 0;

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith("META-INF/")) {
                    continue;
                }
                if (isExcluded(name, excludes)) {
                    continue;
                }
                Path out = SafePaths.resolve(dest, name);
                Path parent = out.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (OutputStream os = Files.newOutputStream(out,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    zis.transferTo(os);
                }
                count++;
            }
        }
        return count;
    }

    private static boolean isExcluded(String name, List<String> excludes) {
        if (excludes == null || excludes.isEmpty()) {
            return false;
        }
        for (String exclude : excludes) {
            if (exclude != null && !exclude.isEmpty() && name.startsWith(exclude)) {
                return true;
            }
        }
        return false;
    }

}
