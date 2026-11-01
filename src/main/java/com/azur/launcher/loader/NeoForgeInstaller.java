package com.azur.launcher.loader;

import com.azur.launcher.config.AppDirs;
import com.azur.launcher.game.JavaDetector;
import com.azur.launcher.game.JavaRuntimeDownloader;
import com.azur.launcher.game.JavaRequirement;
import com.azur.launcher.i18n.I18n;
import com.azur.launcher.net.Checks;
import com.azur.launcher.net.DownloadManager;
import com.azur.launcher.net.HttpUtils;
import com.azur.launcher.net.ProxyConfig;
import com.azur.launcher.profile.InstalledState;
import com.azur.launcher.profile.InstanceStore;
import com.azur.launcher.profile.Profile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Installs NeoForge by running the official installer ({@code java -jar installer --installClient <dir>}).
 * The installer jar is downloaded from the NeoForged Maven and checked against the SHA-1 published next to it
 * and against the SHA-256 pinned in the profile.
 */
public class NeoForgeInstaller {

    private static final long TIMEOUT_MINUTES = 15;

    private final AppDirs dirs;
    private final Consumer<String> logger;
    private DownloadManager.ProgressListener libraryProgress = DownloadManager.ProgressListener.NOOP;

    public NeoForgeInstaller(AppDirs dirs, Consumer<String> logger) {
        this.dirs = dirs;
        this.logger = logger == null ? msg -> { } : logger;
    }

    /** Reports the installer's libraries (file counts) while the launcher fetches them before the installer runs. */
    public NeoForgeInstaller withLibraryProgress(DownloadManager.ProgressListener listener) {
        this.libraryProgress = listener == null ? DownloadManager.ProgressListener.NOOP : listener;
        return this;
    }

    /** Outcome of an installation; {@code message} is localised and safe to show. */
    public record InstallResult(boolean success, String versionId, String message) {
    }

    /**
     * Installs any NeoForge build. The bundled pin is also checked against its pinned SHA-256 and recorded as
     * installed; other builds are checked against the SHA-1 published on the same Maven only.
     */
    public InstallResult install(String mcVersion, String neoForgeVersion, Path gameDir) {
        try {
            if (!InstallEntries.isValidNeoForge(neoForgeVersion)) {
                return fail(I18n.tr("neoforge.bad_version", String.valueOf(neoForgeVersion)));
            }
            Profile pin = InstanceStore.bundledProfile();
            boolean isPin = pin.minecraft().equals(mcVersion) && pin.neoforge().equals(neoForgeVersion);
            InstallResult result = install(mcVersion, neoForgeVersion,
                    isPin ? pin.neoforgeInstallerSha256() : null, gameDir);
            if (result.success() && isPin) {
                new InstalledState(mcVersion, neoForgeVersion, pin.neoforgeInstallerSha256())
                        .write(dirs.instancesDir().resolve(pin.serverId()).resolve("installed.json"));
            }
            return result;
        } catch (IOException e) {
            return fail(I18n.tr("neoforge.failed", e.getMessage()));
        }
    }

    /**
     * Installs NeoForge {@code neoForgeVersion} into {@code gameDir}; the installer must match both the checksum
     * published in the Maven repository and {@code pinnedSha256}. Blocking, call off the UI thread.
     */
    public InstallResult install(String mcVersion, String neoForgeVersion, String pinnedSha256, Path gameDir) {
        try {
            Files.createDirectories(gameDir);
            logger.accept("Installing NeoForge " + neoForgeVersion + " (Minecraft " + mcVersion + ")");
            ensureLauncherProfiles(gameDir);

            Path cache = dirs.cacheDir();
            Files.createDirectories(cache);
            Path installerJar = cache.resolve("neoforge-" + neoForgeVersion + "-installer.jar");
            String url = NeoForgeCatalog.installerUrl(neoForgeVersion);
            String sha1 = HttpUtils.getStringMirrored(url + ".sha1").trim();
            HttpUtils.downloadMirrored(url, installerJar, new Checks(sha1, pinnedSha256, 0));
            InstallerLibraries.fetch(installerJar, gameDir, logger, libraryProgress);

            JavaDetector.JavaInfo java = pickJava(mcVersion);
            Path javaExe = java != null ? java.getJavaExecutable() : downloadedJava(mcVersion);
            if (javaExe == null) {
                return fail(I18n.tr("neoforge.no_java"));
            }
            int exitCode = run(command(javaExe, installerJar, gameDir), gameDir);
            if (exitCode != 0) {
                return fail(I18n.tr("neoforge.exit_code", exitCode));
            }
            String versionId = "neoforge-" + neoForgeVersion;
            if (!Files.exists(gameDir.resolve("versions").resolve(versionId).resolve(versionId + ".json"))) {
                return fail(I18n.tr("neoforge.no_result", versionId));
            }
            return new InstallResult(true, versionId, I18n.tr("neoforge.done", versionId));
        } catch (IOException e) {
            logger.accept("NeoForge install failed: " + e.getMessage());
            return fail(I18n.tr("neoforge.failed", e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(I18n.tr("neoforge.interrupted"));
        }
    }

    private static InstallResult fail(String message) {
        return new InstallResult(false, null, message);
    }

    /** The installer refuses to run without a launcher profile file in the game directory. */
    private void ensureLauncherProfiles(Path gameDir) throws IOException {
        Path profiles = gameDir.resolve("launcher_profiles.json");
        if (!Files.exists(profiles)) {
            Files.writeString(profiles, "{\"profiles\":{},\"settings\":{},\"version\":3}\n");
        }
    }

    /** Pure command builder, separated for tests. */
    static List<String> command(Path javaExe, Path installerJar, Path gameDir) {
        List<String> command = new ArrayList<>();
        command.add(javaExe.toString());
        command.addAll(ProxyConfig.current().jvmArgs());
        command.add("-jar");
        command.add(installerJar.toString());
        command.add("--installClient");
        command.add(gameDir.toString());
        return command;
    }

    private int run(List<String> command, Path workDir) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.isBlank()) {
                        logger.accept("[installer] " + line.strip());
                    }
                }
            } catch (IOException e) {
                logger.accept("[installer] log reading stopped: " + e.getMessage());
            }
        }, "azur-neoforge-install-log");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            logger.accept("Installer timed out after " + TIMEOUT_MINUTES + " minutes");
            return -1;
        }
        reader.join(3000);
        return process.exitValue();
    }

    private Path downloadedJava(String mcVersion) {
        try {
            return new JavaRuntimeDownloader(dirs, logger).ensure(JavaRequirement.ruleMajorFor(mcVersion));
        } catch (IOException e) {
            logger.accept("Java download failed: " + e.getMessage());
            return null;
        }
    }

    private JavaDetector.JavaInfo pickJava(String mcVersion) {
        int required = JavaRequirement.ruleMajorFor(mcVersion);
        List<JavaDetector.JavaInfo> all = JavaDetector.detectAll();
        JavaDetector.JavaInfo best = JavaDetector.pickBest(all, required);
        return best != null ? best : JavaDetector.pickFallback(all, required);
    }
}
