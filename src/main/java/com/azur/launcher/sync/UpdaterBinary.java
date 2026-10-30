package com.azur.launcher.sync;

import com.azur.launcher.net.Hashes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
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

    /**
     * The binary shipped next to the launcher jar: {@code updater/<name>} or {@code <name>} in the jar's folder.
     * This is the normal case for the single-jar distribution, which has no installer and no {@code $APPDIR}.
     */
    static Optional<Path> besideJar(Path codeSource, String fileName) {
        if (codeSource == null || fileName == null || !Files.isRegularFile(codeSource)) {
            return Optional.empty();
        }
        Path dir = codeSource.getParent();
        if (dir == null) {
            return Optional.empty();
        }
        for (Path candidate : List.of(dir.resolve("updater").resolve(fileName), dir.resolve(fileName))) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Where this class was loaded from (the jar in a normal run); null when that cannot be determined. */
    private static Path codeSource() {
        try {
            return Path.of(UpdaterBinary.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The working copy, in order: the path from the settings, the data folder {@code <data>/bin} (extracted from this
     * jar, or the newer copy the updater put there itself), the app image, a copy next to the jar. Throws when there
     * is nothing to run.
     */
    public static Path resolve(String configured, Path binDir) throws IOException {
        String name = fileName();
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim());
            if (!Files.isRegularFile(path)) {
                throw new IOException("Updater not found at " + configured);
            }
            return path;
        }
        Path local = refreshLocalCopy(binDir, name);
        if (local != null) {
            return local;
        }
        Optional<Path> shipped = packaged(System.getProperty("azur.app.dir"), name);
        if (shipped.isPresent()) {
            return shipped.get();
        }
        Optional<Path> beside = besideJar(codeSource(), name);
        if (beside.isPresent()) {
            return beside.get();
        }
        throw new IOException("Updater not found: " + binDir.resolve(name)
                + " (build it with tools/updater/build.sh)");
    }

    /** {@link #refreshLocalCopy(Path, String, byte[])} with the binary carried by this jar. */
    static Path refreshLocalCopy(Path binDir, String name) throws IOException {
        return refreshLocalCopy(binDir, name, readResource(name));
    }

    /**
     * Keeps {@code <binDir>/<name>} in step with the binary this jar ships, without ever overwriting a newer copy the
     * updater put there itself during self-update.
     *
     * <p>{@code <name>.from-jar} records the hash of what we extracted last time. If the file on disk equals that
     * hash, the file is still ours and is replaced when the jar ships something else; if it differs, the updater
     * replaced it, and that copy wins.
     *
     * <p>A file without a marker was dropped by a launcher that did not keep this bookkeeping yet (or by hand). It
     * cannot be told apart from a self-updated copy, so the jar wins once: otherwise a launcher upgrade could never
     * replace an updater build that is too old to speak the machine mode, and pack sync would fail for good.
     *
     * @return the usable copy, or null when the jar carries no binary and nothing is installed yet
     */
    static Path refreshLocalCopy(Path binDir, String name, byte[] shipped) throws IOException {
        Path target = binDir.resolve(name);
        Path marker = binDir.resolve(name + ".from-jar");
        boolean present = Files.isRegularFile(target);
        if (shipped == null) {
            return present ? target : null;
        }
        String shippedHash = sha256(shipped);
        String markerHash = readMarker(marker);
        String currentHash = present ? Hashes.sha256(target) : "";
        if (present && !markerHash.isEmpty() && !currentHash.equals(markerHash)) {
            return target;
        }
        if (present && currentHash.equals(shippedHash)) {
            if (markerHash.isEmpty()) {
                Files.createDirectories(binDir);
                Files.writeString(marker, shippedHash); // adopt a copy of our own build from an older launcher
            }
            return target;
        }
        Files.createDirectories(binDir);
        Path part = target.resolveSibling(name + ".part");
        Files.write(part, shipped);
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(part);
            // a running binary cannot be replaced on some systems: the existing copy stays usable
            if (present) {
                return target;
            }
            throw e;
        }
        target.toFile().setExecutable(true);
        Files.writeString(marker, shippedHash);
        return target;
    }

    private static byte[] readResource(String name) {
        try (InputStream in = UpdaterBinary.class.getResourceAsStream("/updater/" + name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static String readMarker(Path marker) {
        try {
            return Files.isRegularFile(marker) ? Files.readString(marker).strip() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String sha256(byte[] data) {
        try {
            return Hashes.toHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
