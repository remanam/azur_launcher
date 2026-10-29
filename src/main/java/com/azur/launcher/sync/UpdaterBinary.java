package com.azur.launcher.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds the {@code azur-updater} executable. Order: the path from settings, the copy in the data folder
 * ({@code <data>/bin}), then the build bundled in the jar as {@code /updater/azur-updater-<os>-<arch>[.exe]}
 * (extracted once into {@code <data>/bin}). File names match tools/updater/build.sh.
 */
public final class UpdaterBinary {

    private UpdaterBinary() {
    }

    /** {@code azur-updater-<os>-<arch>[.exe]}; os is windows|darwin|linux, arch is amd64|arm64. */
    public static String fileName(String osName, String archName) {
        String os = osName.toLowerCase(Locale.ROOT);
        String osPart = os.contains("win") ? "windows" : os.contains("mac") || os.contains("darwin") ? "darwin" : "linux";
        String arch = archName.toLowerCase(Locale.ROOT);
        String archPart = arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "amd64";
        return "azur-updater-" + osPart + "-" + archPart + (osPart.equals("windows") ? ".exe" : "");
    }

    public static String fileName() {
        return fileName(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** Pure lookup of an existing file: configured path first, then {@code binDir}. */
    public static Optional<Path> locate(String configured, Path binDir, String fileName) {
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim());
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        }
        Path inBin = binDir.resolve(fileName);
        return Files.isRegularFile(inBin) ? Optional.of(inBin) : Optional.empty();
    }

    /** {@link #locate} plus the bundled resource; throws when the updater is nowhere to be found. */
    public static Path resolve(String configured, Path binDir) throws IOException {
        String name = fileName();
        Optional<Path> found = locate(configured, binDir, name);
        if (found.isPresent()) {
            return found.get();
        }
        if (configured != null && !configured.isBlank()) {
            throw new IOException("Updater not found at " + configured);
        }
        try (InputStream in = UpdaterBinary.class.getResourceAsStream("/updater/" + name)) {
            if (in == null) {
                throw new IOException("Updater not found: " + binDir.resolve(name));
            }
            Files.createDirectories(binDir);
            Path target = binDir.resolve(name);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().setExecutable(true);
            return target;
        }
    }
}
