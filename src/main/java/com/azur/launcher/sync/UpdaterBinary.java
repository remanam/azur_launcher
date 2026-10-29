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
 * ({@code <data>/bin}), the one packaged next to the app ({@code $APPDIR/updater}, system property
 * {@code azur.app.dir}, set by the jpackage launcher), then a build embedded in the jar as
 * {@code /updater/azur-updater-<os>-<arch>[.exe]} (extracted once into {@code <data>/bin}). File names match
 * tools/updater/build.sh; the release package copies the binaries in with {@code -Pdist}.
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

    /** The binary shipped in the app image, when this process was started by the packaged launcher. */
    static Optional<Path> packaged(String appDir, String fileName) {
        if (appDir == null || appDir.isBlank()) {
            return Optional.empty();
        }
        Path file = Path.of(appDir.trim()).resolve("updater").resolve(fileName);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /** {@link #locate} plus the packaged and embedded copies; throws when the updater is nowhere to be found. */
    public static Path resolve(String configured, Path binDir) throws IOException {
        String name = fileName();
        Optional<Path> found = locate(configured, binDir, name);
        if (found.isPresent()) {
            return found.get();
        }
        if (configured != null && !configured.isBlank()) {
            throw new IOException("Updater not found at " + configured);
        }
        Optional<Path> shipped = packaged(System.getProperty("azur.app.dir"), name);
        if (shipped.isPresent()) {
            return shipped.get();
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
