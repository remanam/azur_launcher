package com.azur.launcher.game;

import com.azur.launcher.auth.AuthAccount;
import com.azur.launcher.config.LauncherConfig;
import com.azur.launcher.i18n.I18n;
import com.azur.launcher.config.AppDirs;
import com.azur.launcher.net.HttpUtils;
import com.azur.launcher.profile.Instance;
import com.azur.launcher.profile.InstanceStore;
import com.azur.launcher.profile.Profile;
import com.azur.launcher.model.VersionMeta;
import com.azur.launcher.net.DownloadManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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

    /** Asked when the start looks hung; returns true to terminate the game, false to wait two more minutes. */
    public interface HangHandler {
        boolean terminate(String message, List<String> keyLines, Path dumpFile);
    }

    /** Lines of game output kept for the error dialog. */
    public static final int TAIL_LINES = 30;
    public static final String GAME_LOG = "launcher-game.log";

    private final GamePaths paths;
    private final Consumer<String> internalLogger;
    private final DownloadManager.ProgressListener progressListener;

    private final List<LogSink> logSinks = new CopyOnWriteArrayList<>();
    private final List<ExitSink> exitSinks = new CopyOnWriteArrayList<>();

    private Instance fixedInstance;
    private volatile Process process;
    private volatile boolean preparing;
    private volatile Path gameLogFile;
    private volatile HangHandler hangHandler;
    private volatile LaunchWatchdog watchdog;
    private volatile java.util.concurrent.Callable<List<String>> loginArgs;

    public GameLauncher(GamePaths paths, Consumer<String> logger, DownloadManager.ProgressListener progressListener) {
        this.paths = paths;
        this.internalLogger = logger == null ? m -> {
        } : logger;
        this.progressListener = progressListener == null ? DownloadManager.ProgressListener.NOOP : progressListener;
    }

    /** Uses {@code instance} instead of the default one from {@link AppDirs}. */
    public GameLauncher withInstance(Instance instance) {
        this.fixedInstance = instance;
        return this;
    }

    /** Extra JVM arguments chosen at the last moment (login ticket and key file paths); called after the files are prepared. */
    public GameLauncher withLoginArgs(java.util.concurrent.Callable<List<String>> supplier) {
        this.loginArgs = supplier;
        return this;
    }

    public void addLogSink(LogSink sink) {
        logSinks.add(sink);
    }

    public void setHangHandler(HangHandler handler) {
        this.hangHandler = handler;
    }

    public void addExitSink(ExitSink sink) {
        exitSinks.add(sink);
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** The last lines the game printed (stdout and stderr). */
    public List<String> lastLines() {
        Path f = gameLogFile;
        return f == null ? List.of() : LogFiles.tail(f, TAIL_LINES, 64 * 1024);
    }

    /** {@code <instance>/minecraft/logs/launcher-game.log}, the whole output of the last run; null before the first launch. */
    public Path gameLogFile() {
        return gameLogFile;
    }

    public boolean isPreparing() {
        return preparing;
    }

    /**
     * Prepares and starts the game; returns once the process exists (it keeps running in the background).
     * Blocking network and disk work: call from a background thread.
     *
     * @param account the signed-in account; it must be {@link AuthAccount#launchPermitted()}, there is no
     *                fallback account
     */
    public synchronized void launch(LauncherConfig config, AuthAccount account) throws Exception {
        if (isRunning()) {
            log("The game is already running, ignoring the launch request");
            return;
        }
        if (account == null || !account.launchPermitted()) {
            throw new IllegalStateException(I18n.tr("auth.launch_blocked"));
        }
        preparing = true;
        try {
            // stage 2B: everything below the account lines is driven by the pinned profile
            Instance instance = resolveInstance();
            Profile pin = instance.profile();
            HttpUtils.useMirror(pin.mirrorBase());
            String versionId = pin.neoforgeVersionId();
            new PinService(AppDirs.system(), paths, internalLogger).requirePinned(instance);
            Path contentRoot = instance.gameDir();
            Files.createDirectories(contentRoot);

            Path javaExecutable = resolveJava(config, versionId);
            log("Java: " + javaExecutable);

            VersionMeta meta = new VersionManager(paths, internalLogger).getVersionMeta(versionId, false);
            log("Resolved version: " + meta.getId());
            GamePreparer.Prepared prepared = new GamePreparer(
                    paths, internalLogger, progressListener, config.getDownloadThreads())
                    .prepare(versionId, meta, false);

            LaunchProfile profile = new LaunchProfile();
            profile.setVersionId(versionId);
            profile.setVersionType(meta.getType() == null ? "release" : meta.getType());
            profile.setJavaExecutable(javaExecutable);
            profile.setGameDir(contentRoot);
            profile.setAssetsDir(paths.assetsDir());
            profile.setLibrariesDir(paths.librariesDir());
            profile.setNativesDir(prepared.nativesDir());
            profile.setClientJar(prepared.clientJar());
            profile.setClasspath(prepared.classpath());
            profile.setAssetIndexName(prepared.assetIndexName());
            profile.setMaxMemoryMb(pin.ramMaxMb());
            profile.setMinMemoryMb(pin.ramMinMb());
            List<String> jvmArgs = new java.util.ArrayList<>(pin.jvmArgs());
            if (loginArgs != null) {
                jvmArgs.addAll(loginArgs.call());
            }
            profile.setExtraJvmArgs(jvmArgs);
            profile.setWindowWidth(config.getWindowWidth());
            profile.setWindowHeight(config.getWindowHeight());
            profile.setAccount(LaunchContext.from(account));
            log("Account: " + account.getUsername() + " (" + account.getUserType() + ")");

            PlatformRules.setFeature("has_custom_resolution",
                    config.getWindowWidth() > 0 && config.getWindowHeight() > 0);

            List<String> command = CommandBuilder.build(profile, meta);
            log("Command: " + maskSecrets(command));
            long startedAt = System.currentTimeMillis();

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(contentRoot.toFile());
            processBuilder.redirectErrorStream(true);
            gameLogFile = contentRoot.resolve("logs").resolve(GAME_LOG);
            Files.createDirectories(gameLogFile.getParent());
            Files.deleteIfExists(gameLogFile);
            scanned = 0;

            // the game writes straight to the file: no pipe in the launcher JVM, so it can never fill up and block the game
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(gameLogFile.toFile()));
            Process p = processBuilder.start();
            this.process = p;
            log("Minecraft started, PID " + p.pid());

            startWatchdog(p, config, contentRoot);

            Thread waitThread = new Thread(() -> {
                int code;
                try {
                    code = p.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    code = -1;
                }
                log("Game process exited with code " + code);
                if (code != 0) {
                    CrashReports.newestSince(instance.crashReportsDir(), startedAt)
                            .ifPresent(report -> log("Crash report: " + report));
                }
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

    private void startWatchdog(Process p, LauncherConfig config, Path contentRoot) {
        HangHandler handler = hangHandler;
        if (handler == null || (config.getHangSeconds() <= 0 && config.getStartupLimitSeconds() <= 0)) {
            return;
        }
        LaunchWatchdog dog = new LaunchWatchdog(config.getHangSeconds(), config.getStartupLimitSeconds(),
                System.currentTimeMillis());
        watchdog = dog;
        Thread t = new Thread(() -> {
            try {
                while (p.isAlive() && !dog.ready()) {
                    Thread.sleep(5000);
                    scanReady(dog);
                    dog.observeSize(logsSize(contentRoot), System.currentTimeMillis());
                    if (p.isAlive() && dog.hung(System.currentTimeMillis())) {
                        if (handleHang(p, contentRoot, handler)) {
                            return;
                        }
                        dog.postpone(120_000, System.currentTimeMillis());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "azur-game-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private long scanned;

    /** Looks for the ready marker only in the bytes added since the last look. */
    private void scanReady(LaunchWatchdog dog) {
        long size = LogFiles.size(gameLogFile);
        if (size <= scanned) {
            return;
        }
        String chunk = LogFiles.readFrom(gameLogFile, scanned, 4 * 1024 * 1024);
        scanned = size;
        if (LaunchWatchdog.isReadyLine(chunk)) {
            dog.markReady();
        }
    }

    private static long logsSize(Path contentRoot) {
        long sum = 0;
        for (String name : new String[] {"latest.log", GAME_LOG}) {
            try {
                sum += Files.size(contentRoot.resolve("logs").resolve(name));
            } catch (IOException ignored) {
                // not created yet
            }
        }
        return sum;
    }

    /** Takes a thread dump, writes it, asks the handler; returns true if the game was terminated. */
    private boolean handleHang(Process p, Path contentRoot, HangHandler handler) throws InterruptedException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (!windows) {
            try {
                new ProcessBuilder("kill", "-3", Long.toString(p.pid())).start().waitFor();
            } catch (IOException e) {
                log("Thread dump request failed: " + e.getMessage());
            }
            Thread.sleep(2000);
        }
        List<String> dump = List.of();
        dump = HangDiagnosis.lastDump(LogFiles.tail(gameLogFile, 100_000, 8 * 1024 * 1024));
        HangDiagnosis diagnosis = HangDiagnosis.parse(dump);
        String message = diagnosis != null ? diagnosis.message(I18n.language())
                : I18n.tr("hang.unknown");
        Path dumpFile = null;
        if (!dump.isEmpty()) {
            try {
                dumpFile = contentRoot.resolve("logs").resolve("hang-dump-" + System.currentTimeMillis() + ".txt");
                Files.write(dumpFile, dump, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log("Cannot write the hang dump: " + e.getMessage());
            }
        }
        log("HANG: " + message);
        dump.stream().limit(40).forEach(l -> log("[hang-dump] " + l));
        boolean kill = handler.terminate(message, dump.stream().limit(40).toList(), dumpFile);
        if (kill) {
            stop();
        }
        return kill;
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
    private Path resolveJava(LauncherConfig config, String versionId) throws IOException {
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
        try {
            log("No Java " + required + " installed, downloading Mojang's runtime");
            return new JavaRuntimeDownloader(AppDirs.system(), this::log).ensure(required);
        } catch (IOException e) {
            log("Java download failed: " + e.getMessage());
            throw new IOException(I18n.tr("launch.no_java", required, JavaRequirement.INSTALL_URL, e.getMessage()));
        }
    }

    /** The single instance of this install; tests and the UI may inject another through the constructor. */
    private Instance resolveInstance() throws IOException {
        return fixedInstance != null ? fixedInstance : new InstanceStore(AppDirs.system()).loadDefault();
    }

    private Path versionJsonOrNull(String versionId) {
        try {
            return versionId == null || versionId.isBlank() ? null : paths.versionJson(versionId.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
