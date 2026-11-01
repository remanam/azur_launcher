package com.azur.launcher.ui;

import com.azur.launcher.auth.AccountStore;
import com.azur.launcher.api.CommunityApi;
import com.azur.launcher.auth.PlayerTokenStore;
import com.azur.launcher.config.AppDirs;
import com.azur.launcher.config.ConfigManager;
import com.azur.launcher.config.LauncherConfig;
import com.azur.launcher.config.PackSettings;
import com.azur.launcher.game.GamePaths;
import com.azur.launcher.game.RotatingLog;
import com.azur.launcher.net.HttpUtils;
import com.azur.launcher.net.ProxyConfig;
import com.azur.launcher.net.ProxySettings;

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
    final ProxySettings proxySettings;
    /** Token of the community API (registration, roles); empty until the player registers. */
    final PlayerTokenStore playerTokens;
    final HostServices host;
    final Stage stage;
    /** Server list and pack sync settings (servers.json address, API address). */
    final PackSettings pack;
    /** Background work (network, disk). Daemon threads, so they never block exit. */
    final ExecutorService io = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "azur-io");
        thread.setDaemon(true);
        return thread;
    });

    /** {@code <data>/logs/launcher.log}: 1 MB, three old files kept. */
    private final RotatingLog logFile;
    private final StringBuilder logBuffer = new StringBuilder();
    private final List<Runnable> accountListeners = new ArrayList<>();
    private Consumer<String> logView = line -> { };
    private Consumer<String> statusView = text -> { };
    private DoubleConsumer progressView = value -> { };

    AppContext(AppDirs dirs, ConfigManager configManager, LauncherConfig config, AccountStore accounts,
               ProxySettings proxySettings,
               HostServices host, Stage stage) {
        this.dirs = dirs;
        this.logFile = new RotatingLog(dirs.logsDir().resolve("launcher.log"), 1_000_000, 3);
        this.configManager = configManager;
        this.config = config;
        this.accounts = accounts;
        this.proxySettings = proxySettings;
        ProxyConfig.set(ProxyConfig.of(proxySettings.proxyHost(), proxySettings.proxyPort(), "", ""));
        logFile.append("Proxy: " + ProxyConfig.current().describe() + " (only from Settings > Advanced)");
        this.playerTokens = PlayerTokenStore.system(dirs.playerTokenVaultFile());
        this.host = host;
        this.stage = stage;
        this.pack = PackSettings.load(dirs.packSettingsFile());
        // The signed server list and our own mirror live on our https host; allow it before the first request
        // (the policy is a central allowlist, so a host not added here is never fetched).
        HttpUtils.allowHosts(this.pack.getServersUrl());
    }

    /**
     * Applies what the community API reported: the server address (log only). The player account and the server
     * list stay the source of truth.
     */
    void applyCommunity(CommunityApi.Session session) {
        if (session == null) {
            return;
        }
        if (session.server() != null && !session.server().address().isBlank()) {
            log("Community API: server " + session.server().address() + ":" + session.server().port());
        }
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
        if (!line.startsWith("[game] ")) {
            logFile.append(line);
        }
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
