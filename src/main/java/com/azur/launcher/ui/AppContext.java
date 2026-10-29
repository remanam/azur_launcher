package com.azur.launcher.ui;

import com.azur.launcher.auth.AccountStore;
import com.azur.launcher.auth.AuthAccount;
import com.azur.launcher.auth.AuthSettings;
import com.azur.launcher.config.AppDirs;
import com.azur.launcher.config.ConfigManager;
import com.azur.launcher.config.LauncherConfig;
import com.azur.launcher.config.PackSettings;
import com.azur.launcher.game.GamePaths;

import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** State and services shared by all pages: config, accounts, background executor, log and status line. */
final class AppContext {

    private static final int LOG_LIMIT = 200_000;
    private static final int LOG_TRIM_TO = 100_000;

    final AppDirs dirs;
    final ConfigManager configManager;
    final LauncherConfig config;
    final AccountStore accounts;
    final AuthSettings authSettings;
    /** Set only by the debug build's account provider; always null in release. */
    AuthAccount debugAccount;
    final HostServices host;
    final Stage stage;
    /** Server list and pack sync settings (invite, servers.json address, updater path). */
    final PackSettings pack;
    /** Background work (network, disk). Daemon threads, so they never block exit. */
    final ExecutorService io = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "azur-io");
        thread.setDaemon(true);
        return thread;
    });

    private final StringBuilder logBuffer = new StringBuilder();
    private final List<Runnable> accountListeners = new ArrayList<>();
    private Consumer<String> logView = line -> { };
    private Consumer<String> statusView = text -> { };
    private DoubleConsumer progressView = value -> { };

    AppContext(AppDirs dirs, ConfigManager configManager, LauncherConfig config, AccountStore accounts,
               AuthSettings authSettings,
               HostServices host, Stage stage) {
        this.dirs = dirs;
        this.configManager = configManager;
        this.config = config;
        this.accounts = accounts;
        this.authSettings = authSettings;
        this.host = host;
        this.stage = stage;
        this.pack = PackSettings.load(dirs.packSettingsFile());
    }

    void savePack() {
        try {
            pack.save(dirs.packSettingsFile());
        } catch (Exception e) {
            log("Cannot save pack settings: " + e.getMessage());
        }
    }

    GamePaths gamePaths() {
        return new GamePaths(gameDir());
    }

    Path gameDir() {
        String configured = config.getGameDir();
        return configured == null || configured.isBlank() ? dirs.gameDir() : Path.of(configured);
    }

    void saveConfig() {
        try {
            configManager.save(config);
        } catch (Exception ignored) {
            // settings are re-saved on the next change; a failed write must not break the UI
        }
    }

    /** Appends to the log; safe to call from any thread. */
    void log(String line) {
        Platform.runLater(() -> {
            logBuffer.append(line).append('\n');
            if (logBuffer.length() > LOG_LIMIT) {
                logBuffer.delete(0, logBuffer.length() - LOG_TRIM_TO);
            }
            logView.accept(line);
        });
    }

    void setStatus(String text) {
        Platform.runLater(() -> statusView.accept(text));
    }

    /** Download progress in 0..1, or a negative value to hide it. */
    void setProgress(double fraction) {
        Platform.runLater(() -> progressView.accept(fraction));
    }

    void bindProgress(DoubleConsumer view) {
        this.progressView = view;
    }

    String logText() {
        return logBuffer.toString();
    }

    void bindLog(Consumer<String> view) {
        this.logView = view;
    }

    void bindStatus(Consumer<String> view) {
        this.statusView = view;
    }

    void onAccountsChanged(Runnable listener) {
        accountListeners.add(listener);
    }

    void fireAccountsChanged() {
        for (Runnable listener : accountListeners) {
            listener.run();
        }
    }

    void openInSystem(Path path) {
        host.showDocument(path.toUri().toString());
    }

    void shutdown() {
        io.shutdownNow();
    }
}
