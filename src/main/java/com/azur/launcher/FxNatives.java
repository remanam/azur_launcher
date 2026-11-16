package com.azur.launcher;

import com.azur.launcher.config.AppDirs;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The cross-platform jar carries JavaFX natives for every target under {@code natives/<platform>/} (the file names
 * clash between macOS arm64 and x64, so they cannot sit at the jar root). Before JavaFX starts, the set of this
 * machine is extracted to the cache and appended to {@code java.library.path}, which JavaFX reads when it loads
 * a library. Without a {@code natives/} folder (IDE or {@code javafx:run}) this does nothing.
 */
final class FxNatives {

    private static final String ROOT = "natives/";

    private FxNatives() {
    }

    static String platform(String osName, String arch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String cpu = arch.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "win";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return cpu.equals("aarch64") || cpu.equals("arm64") ? "mac-aarch64" : "mac";
        }
        return "linux";
    }

    static void prepare() {
        try {
            Path jar = Path.of(FxNatives.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(jar)) {
                return;
            }
            String os = System.getProperty("os.name", "");
            String arch = System.getProperty("os.arch", "");
            String platform = platform(os, arch);
            String prefix = ROOT + platform + "/";
            try (JarFile file = new JarFile(jar.toFile())) {
                boolean hasNatives = false;
                Path target = null;
                for (JarEntry entry : java.util.Collections.list(file.entries())) {
                    if (entry.isDirectory() || !entry.getName().startsWith(ROOT)) {
                        continue;
                    }
                    hasNatives = true;
                    if (!entry.getName().startsWith(prefix)) {
                        continue;
                    }
                    if (Objects.isNull(target)) {
                        target = AppDirs.system().cacheDir().resolve("fxnatives")
                                .resolve(platform + "-" + jar.toFile().length());
                        Files.createDirectories(target);
                    }
                    try {
                        extract(file, entry, target.resolve(entry.getName().substring(prefix.length())));
                    } catch (IOException e) {
                        // One locked or unwritable native must not hide the others; JavaFX reports the first missing one.
                        System.err.println("JavaFX native " + entry.getName() + " was not extracted: " + e);
                    }
                }
                if (Objects.nonNull(target)) {
                    String old = System.getProperty("java.library.path", "");
                    System.setProperty("java.library.path", target + (old.isEmpty() ? "" : java.io.File.pathSeparator + old));
                } else if (hasNatives) {
                    System.err.println("This jar carries no JavaFX natives for " + platform + " (os.name=" + os
                            + ", os.arch=" + arch + "). Build the cross-platform jar with: ./gradlew releaseJar");
                }
            }
        } catch (IOException | URISyntaxException | RuntimeException e) {
            System.err.println("JavaFX natives were not prepared: " + e);
        }
    }

    private static void extract(JarFile file, JarEntry entry, Path out) throws IOException {
        if (Files.isRegularFile(out) && Files.size(out) == entry.getSize()) {
            return;
        }
        Path part = out.resolveSibling(out.getFileName() + ".part");
        try (InputStream in = file.getInputStream(entry)) {
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(part, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
