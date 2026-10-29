package com.azur.launcher.game;

import com.azur.launcher.auth.AuthAccount;
import com.azur.launcher.auth.OfflineAccount;
import com.azur.launcher.config.LauncherConfig;
import com.azur.launcher.i18n.I18n;
import com.azur.launcher.model.AssetObjects;
import com.azur.launcher.model.DownloadInfo;
import com.azur.launcher.model.VersionMeta;
import com.azur.launcher.net.DownloadManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Launch orchestration: prepare files (client jar, libraries, assets, natives), build the command line,
 * start the process and forward its output. Log lines are English diagnostics; errors shown to the player
 * come from {@link I18n}.
 */
public class GameLauncher {

    /** Receives log lines (game output and launcher messages). */
    public interface LogSink {
        void log(String line);
    }

    /** Receives the exit code of the game process. */
    public interface ExitSink {
        void onExit(int exitCode);
    }

    private final GamePaths paths;
    private final Consumer<String> internalLogger;
    private final DownloadManager.ProgressListener progressListener;

    private final List<LogSink> logSinks = new CopyOnWriteArrayList<>();
    private final List<ExitSink> exitSinks = new CopyOnWriteArrayList<>();

    private volatile Process process;
    private volatile boolean preparing;

    public GameLauncher(GamePaths paths, Consumer<String> logger, DownloadManager.ProgressListener progressListener) {
        this.paths = paths;
        this.internalLogger = logger == null ? m -> {
        } : logger;
        this.progressListener = progressListener == null ? DownloadManager.ProgressListener.NOOP : progressListener;
    }

    public void addLogSink(LogSink sink) {
        logSinks.add(sink);
    }

    public void addExitSink(ExitSink sink) {
        exitSinks.add(sink);
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    public boolean isPreparing() {
        return preparing;
    }

    /**
     * Prepares and starts the game; returns once the process exists (it keeps running in the background).
     * Blocking network and disk work: call from a background thread.
     *
     * @param account the signed-in account; when null the offline account from the config is used
     *                (stage 2 removes that fallback from release builds)
     */
    public synchronized void launch(LauncherConfig config, AuthAccount account) throws Exception {
        if (isRunning()) {
            log("The game is already running, ignoring the launch request");
            return;
        }
        AuthAccount effectiveAccount = account != null
                ? account
                : OfflineAccount.of(config.getUsername(), config.getUuid());
        preparing = true;
        try {
            String versionId = config.getVersionId();
            Path contentRoot = paths.contentRoot(versionId);
            Files.createDirectories(contentRoot);

            Path javaExecutable = resolveJava(config);
            log("Java: " + javaExecutable);

            VersionMeta meta = new VersionManager(paths, internalLogger).getVersionMeta(versionId, false);
            log("Resolved version: " + meta.getId());

            List<DownloadManager.Task> tasks = new ArrayList<>();
            String jarId = (meta.getJar() != null && !meta.getJar().isBlank()) ? meta.getJar() : versionId;
            Path clientJar = paths.clientJar(jarId);
            DownloadInfo client = meta.getClientDownload();
            if (client != null && client.getUrl() != null && !client.getUrl().isBlank()) {
                tasks.add(new DownloadManager.Task(client.getUrl(), clientJar, client.getSha1(), client.getSize()));
            } else if (!Files.isRegularFile(clientJar)) {
                throw new IOException(I18n.tr("launch.no_client", clientJar));
            }

            LibraryManager libraryManager = new LibraryManager(paths, internalLogger);
            tasks.addAll(libraryManager.buildDownloadTasks(meta.getLibraries()));

            AssetManager assetManager = new AssetManager(paths, internalLogger);
            String assetIndexName = meta.getAssets();
            if (meta.getAssetIndex() != null) {
                assetManager.ensureIndex(meta.getAssetIndex());
                AssetObjects assetIndex = assetManager.readIndex(meta.getAssetIndex());
                tasks.addAll(assetManager.buildDownloadTasks(assetIndex));
                if (assetIndexName == null || assetIndexName.isBlank()) {
                    assetIndexName = meta.getAssetIndex().getId();
                }
            } else if (assetIndexName == null) {
                assetIndexName = "legacy";
            }

            log(tasks.size() + " files to process (existing ones are skipped), " + config.getDownloadThreads()
                    + " threads");
            DownloadManager downloadManager = new DownloadManager(
                    config.getDownloadThreads(), internalLogger, progressListener);
            if (!downloadManager.downloadAll(tasks)) {
                throw new IOException(I18n.tr("launch.download_failed"));
            }

            Path nativesDir = paths.nativesDir(versionId);
            Files.createDirectories(nativesDir);
            int extracted = 0;
            for (LibraryManager.NativeEntry entry : libraryManager.collectNatives(meta.getLibraries())) {
                extracted += NativeExtractor.extract(entry.zip, nativesDir, entry.exclude);
            }
            log("Natives extracted: " + extracted + " files -> " + nativesDir);

            List<Path> classpathEntries = new ArrayList<>(libraryManager.buildClasspath(meta.getLibraries()));
            classpathEntries.add(clientJar);
            String classpath = classpathEntries.stream()
                    .map(Path::toString)
                    .collect(Collectors.joining(File.pathSeparator));

            LaunchProfile profile = new LaunchProfile();
            profile.setVersionId(versionId);
            profile.setVersionType(meta.getType() == null ? "release" : meta.getType());
            profile.setJavaExecutable(javaExecutable);
            profile.setGameDir(contentRoot);
            profile.setAssetsDir(paths.assetsDir());
            profile.setLibrariesDir(paths.librariesDir());
            profile.setNativesDir(nativesDir);
            profile.setClientJar(clientJar);
            profile.setClasspath(classpath);
            profile.setAssetIndexName(assetIndexName);
            profile.setMaxMemoryMb(config.getMaxMemoryMb());
            profile.setMinMemoryMb(config.getMinMemoryMb());
            profile.setExtraJvmArgs(config.getJvmArgs());
            profile.setWindowWidth(config.getWindowWidth());
            profile.setWindowHeight(config.getWindowHeight());
            profile.setAccount(effectiveAccount);
            log("Account: " + effectiveAccount.getUsername() + " (" + effectiveAccount.getUserType() + ")");

            PlatformRules.setFeature("has_custom_resolution",
                    config.getWindowWidth() > 0 && config.getWindowHeight() > 0);

            List<String> command = CommandBuilder.build(profile, meta);
            log("Command: " + maskSecrets(command));

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(contentRoot.toFile());
            processBuilder.redirectErrorStream(true);

            Process p = processBuilder.start();
            this.process = p;
            log("Minecraft started, PID " + p.pid());

            Thread stdoutThread = new Thread(() -> pumpOutput(p), "azur-game-stdout");
            stdoutThread.setDaemon(true);
            stdoutThread.start();

            Thread waitThread = new Thread(() -> {
                int code;
                try {
                    code = p.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    code = -1;
                }
                log("Game process exited with code " + code);
                for (ExitSink sink : exitSinks) {
                    try {
                        sink.onExit(code);
                    } catch (RuntimeException ignored) {
                        // a listener failure must not affect the others
                    }
                }
            }, "azur-game-wait");
            waitThread.setDaemon(true);
            waitThread.start();
        } finally {
            preparing = false;
        }
    }

    /** Stops the game: polite destroy, forced after five seconds. */
    public synchronized void stop() {
        Process p = process;
        if (p == null || !p.isAlive()) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    private void pumpOutput(Process p) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log("[game] " + line);
            }
        } catch (IOException ignored) {
            // the stream closes when the process exits
        }
    }

    private void log(String line) {
        internalLogger.accept(line);
        for (LogSink sink : logSinks) {
            try {
                sink.log(line);
            } catch (RuntimeException ignored) {
                // a sink failure must not affect the others
            }
        }
    }

    /** Hides the access token in a logged command line. */
    static String maskSecrets(List<String> command) {
        StringBuilder sb = new StringBuilder();
        boolean maskNext = false;
        for (String arg : command) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (maskNext && arg != null && !arg.startsWith("--")) {
                sb.append("***");
                maskNext = false;
                continue;
            }
            sb.append(arg);
            if ("--accessToken".equals(arg)) {
                maskNext = true;
            }
        }
        return sb.toString();
    }

    /**
     * Java choice: a configured path wins when its major version matches; otherwise the best detected runtime
     * for the version's requirement, then the closest other one.
     */
    private Path resolveJava(LauncherConfig config) throws IOException {
        String versionId = config.getVersionId();
        int required = JavaRequirement.requiredMajor(versionId, versionJsonOrNull(versionId));

        List<JavaDetector.JavaInfo> candidates = JavaDetector.detectAll();
        for (JavaDetector.JavaInfo info : candidates) {
            log("Detected " + info);
        }

        JavaDetector.JavaInfo manual = null;
        if (config.getJavaPath() != null && !config.getJavaPath().isBlank()) {
            Path configured = Path.of(config.getJavaPath());
            if (Files.isRegularFile(configured)) {
                manual = JavaDetector.probeExecutable(configured);
                if (manual == null) {
                    log("Configured Java cannot be identified, using it as is: " + configured);
                    return configured;
                }
                if (manual.getMajorVersion() == required && JavaDetector.isArchCompatible(manual)) {
                    return configured;
                }
                log("Configured Java " + manual.getVersion() + " does not match the required Java " + required);
            } else if (!config.isAutoDetectJava()) {
                throw new IOException(I18n.tr("launch.bad_java_path", configured));
            }
        }

        JavaDetector.JavaInfo best = JavaDetector.pickBest(candidates, required);
        if (best != null) {
            return best.getJavaExecutable();
        }
        JavaDetector.JavaInfo fallback = JavaDetector.pickFallback(candidates, required);
        if (fallback != null) {
            log("No Java " + required + " found, using Java " + fallback.getVersion());
            return fallback.getJavaExecutable();
        }
        if (manual != null) {
            return manual.getJavaExecutable();
        }
        throw new IOException(I18n.tr("launch.no_java", required));
    }

    private Path versionJsonOrNull(String versionId) {
        try {
            return versionId == null || versionId.isBlank() ? null : paths.versionJson(versionId.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
