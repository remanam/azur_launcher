package com.azur.launcher.game;

import com.azur.launcher.config.AppDirs;
import com.azur.launcher.i18n.I18n;
import com.azur.launcher.loader.NeoForgeInstaller;
import com.azur.launcher.model.VersionMeta;
import com.azur.launcher.net.DownloadManager;
import com.azur.launcher.profile.Instance;
import com.azur.launcher.profile.InstalledState;
import com.azur.launcher.profile.Profile;
import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Compares what is installed with the pinned profile; installs and repairs. */
public final class PinService {

    private static final Gson GSON = new Gson();

    /** Installed versions differ from the pin; the launcher offers repair. */
    public static final class PinMismatchException extends IOException {
        private final List<String> problems;

        public PinMismatchException(List<String> problems) {
            super(I18n.tr("launch.pin_mismatch", String.join("; ", problems)));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    private final AppDirs dirs;
    private final GamePaths paths;
    private final Consumer<String> logger;

    public PinService(AppDirs dirs, GamePaths paths, Consumer<String> logger) {
        this.dirs = dirs;
        this.paths = paths;
        this.logger = logger;
    }

    /** Empty list means the installation matches the pin. */
    public List<String> check(Instance instance) {
        Profile pin = instance.profile();
        List<String> problems = new ArrayList<>();
        InstalledState state = instance.installed();
        if (!pin.minecraft().equals(state.minecraft())) {
            problems.add("Minecraft " + state.minecraft() + " installed, " + pin.minecraft() + " pinned");
        }
        if (!pin.neoforge().equals(state.neoforge())) {
            problems.add("NeoForge " + state.neoforge() + " installed, " + pin.neoforge() + " pinned");
        }
        if (!pin.neoforgeInstallerSha256().equals(state.installerSha256())) {
            problems.add("installer hash differs from the pin");
        }
        Path json = paths.versionJson(pin.neoforgeVersionId());
        if (!Files.isRegularFile(json)) {
            problems.add("missing " + json.getFileName());
        } else if (!pin.minecraft().equals(inheritsFrom(json))) {
            problems.add(json.getFileName() + " is not based on Minecraft " + pin.minecraft());
        }
        return problems;
    }

    public void requirePinned(Instance instance) throws IOException {
        List<String> problems = check(instance);
        if (!problems.isEmpty()) {
            throw new PinMismatchException(problems);
        }
    }

    /** Installs the pinned NeoForge when the installation does not match; records the result. */
    public void installIfNeeded(Instance instance) throws IOException {
        if (check(instance).isEmpty()) {
            return;
        }
        Profile pin = instance.profile();
        NeoForgeInstaller.InstallResult result = new NeoForgeInstaller(dirs, logger)
                .install(pin.minecraft(), pin.neoforge(), pin.neoforgeInstallerSha256(), paths.root());
        if (!result.success()) {
            throw new IOException(result.message());
        }
        new InstalledState(pin.minecraft(), pin.neoforge(), pin.neoforgeInstallerSha256())
                .write(instance.installedFile());
    }

    /**
     * Installs the pin and then fetches the game files it needs (client jar, libraries, assets) with byte progress.
     * Valid files on disk are skipped, so repeating this after an interruption downloads only the rest.
     */
    public void installIfNeeded(Instance instance, int threads, DownloadManager.ProgressListener progress)
            throws IOException {
        installIfNeeded(instance);
        String versionId = instance.profile().neoforgeVersionId();
        VersionMeta meta = new VersionManager(paths, logger).getVersionMeta(versionId, false);
        new GamePreparer(paths, logger, progress, threads).prepare(versionId, meta, false);
    }

    /**
     * Verify files: reinstall on pin mismatch, then re-download the version JSONs and re-hash every library,
     * asset and the client jar.
     */
    public void repair(Instance instance, int threads, DownloadManager.ProgressListener progress)
            throws IOException {
        installIfNeeded(instance);
        String versionId = instance.profile().neoforgeVersionId();
        VersionMeta meta = new VersionManager(paths, logger).getVersionMeta(versionId, true);
        new GamePreparer(paths, logger, progress, threads).prepare(versionId, meta, true);
    }

    private static String inheritsFrom(Path versionJson) {
        try {
            VersionMeta meta = GSON.fromJson(Files.readString(versionJson, StandardCharsets.UTF_8), VersionMeta.class);
            return meta == null ? null : meta.getInheritsFrom();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
