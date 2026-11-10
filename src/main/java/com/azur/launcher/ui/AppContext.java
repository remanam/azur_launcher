package com.azur.launcher.ui;

import com.azur.launcher.account.AccountController;
import com.azur.launcher.account.AccountFlowState;
import com.azur.launcher.account.HttpSiteApi;
import com.azur.launcher.account.SessionStorage;
import com.azur.launcher.account.SiteAuth;
import com.azur.launcher.auth.AccountStore;
import com.azur.launcher.auth.StringVault;
import com.azur.launcher.api.CommunityApi;
import com.azur.launcher.auth.PlayerTokenStore;
import com.azur.launcher.config.AppDirs;
import com.azur.launcher.AppVersion;
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
    /** Sign-in through the site (device flow); {@code null} in a dev run without a servers URL. */
    final SiteAuth site;
    /** Account block state + background sign-in; {@code null} together with {@link #site}. */
    final AccountController accountController;
    final HostServices host;
    final Stage stage;
    /** Server list and pack sync settings (servers.json address, API address). */
    final PackSettings pack;
    /** Anonymous telemetry, on by default; a no-op once the player switches it off in Advanced. */
    final com.azur.launcher.telemetry.TelemetryClient telemetry;
    /** Root-signed working keys (servers, pack, launcher); every signature check asks it. */
    final com.azur.launcher.keys.KeysetService keysets;
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
    private final List<Consumer<AccountFlowState>> accountViews = new java.util.concurrent.CopyOnWriteArrayList<>();
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
        this.keysets = createKeysets(dirs, this.pack.getServersUrl());
        config.migrateTelemetryNotice(accounts.selected() != null);
        this.telemetry = new com.azur.launcher.telemetry.TelemetryClient(config::isTelemetrySendable, config::getTelemetryId, AppVersion.APP,
                telemetrySender(this.pack.getServersUrl()));
        this.site = createSiteAuth(dirs, this.pack.getServersUrl());
        this.accountController = site == null ? null : new AccountController(site, io, state -> Platform.runLater(() -> accountViews.forEach(v -> v.accept(state))),
                url -> Platform.runLater(() -> host.showDocument(url)), AppContext::deviceName, this::siteSessionChanged);
        if (accountController != null) {
            accountController.load();
        }
    }

    /** The site origin of the servers URL (https only); a dev run without one sends nothing. */
    private static com.azur.launcher.telemetry.TelemetrySender telemetrySender(String serversUrl) {
        try {
            java.net.URI u = java.net.URI.create(serversUrl);
            if ("https".equals(u.getScheme()) && u.getHost() != null) {
                return new com.azur.launcher.telemetry.HttpTelemetrySender("https://" + u.getHost());
            }
        } catch (IllegalArgumentException ignored) {
            // falls through to the silent sender
        }
        return (path, json) -> { };
    }

    private static String deviceName() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException | RuntimeException e) {
            host = "";
        }
        return "Азур Лаунчер" + (host.isBlank() ? "" : " (" + host + ")");
    }

    /** Signed in: the account nick becomes the selected player; signed out: the saved nicks apply again. */
    private void siteSessionChanged(java.util.Optional<com.azur.launcher.account.SiteSession> session) {
        session.ifPresent(s -> {
            if (com.azur.launcher.auth.OfflineAccount.isValidName(s.nick())) {
                accounts.selectOffline(com.azur.launcher.auth.OfflineAccount.of(s.nick()));
                try {
                    accounts.save();
                } catch (java.io.IOException e) {
                    log("Cannot save accounts: " + e.getMessage());
                }
            }
        });
        Platform.runLater(this::fireAccountsChanged);
    }

    /** The saved nick counts as a player only without a site (dev run) or when `allow_anonymous` is on; otherwise a site sign-in is needed. */
    com.azur.launcher.auth.AuthAccount anonymousAccount() {
        return site == null || config.isAllowAnonymous() ? accounts.selected() : null;
    }

    /** Who plays now: the signed-in site account (nick in exact case), else {@link #anonymousAccount()}. */
    com.azur.launcher.auth.AuthAccount currentPlayer() {
        if (site != null && site.session().isPresent()
                && com.azur.launcher.auth.OfflineAccount.isValidName(site.session().get().nick())) {
            return com.azur.launcher.auth.OfflineAccount.of(site.session().get().nick());
        }
        return anonymousAccount();
    }

    /** A new account block bound to the shared state (the wizard and «Дополнительно» each have one). */
    SiteLoginPanel sitePanel() {
        String origin = siteOrigin(pack.getServersUrl());
        SiteLoginPanel panel = new SiteLoginPanel(accountController, origin + "/account", origin + "/register", origin + "/reset",
                config.isAllowCodeLogin(), url -> host.showDocument(url), System::currentTimeMillis);
        bindAccount(panel::show);
        return panel;
    }

    /** The account block (Home wizard, Advanced) listens here; the last state is replayed to a new listener. */
    void bindAccount(Consumer<AccountFlowState> view) {
        accountViews.add(view);
        if (accountController != null) {
            view.accept(accountController.state());
        }
    }

    /** Embedded roots (a dev run may override them with {@code <data>/root_keys.txt}) + the legacy servers key. */
    private com.azur.launcher.keys.KeysetService createKeysets(AppDirs dirs, String serversUrl) {
        com.azur.launcher.keys.RootKeys roots;
        try {
            boolean dev = !System.getProperty(AppDirs.HOME_PROPERTY, "").isBlank();
            roots = dev && java.nio.file.Files.isRegularFile(dirs.rootKeysFile())
                    ? com.azur.launcher.keys.RootKeys.parse(java.nio.file.Files.readString(dirs.rootKeysFile()))
                    : com.azur.launcher.keys.RootKeys.embedded();
        } catch (java.io.IOException | RuntimeException e) {
            log("Root keys unreadable, keyset disabled: " + e.getMessage());
            roots = com.azur.launcher.keys.RootKeys.NONE;
        }
        java.security.PublicKey legacy = null;
        try {
            legacy = com.azur.launcher.servers.ServerListService.appKey(dirs.serversKeyFile());
        } catch (java.io.IOException | java.security.GeneralSecurityException e) {
            log("Legacy servers key unreadable: " + e.getMessage());
        }
        String origin = siteOrigin(serversUrl);
        return new com.azur.launcher.keys.KeysetService(dirs.keysetFile(), roots, legacy,
                origin.isEmpty() ? "" : origin + "/dl/keyset.json", HttpUtils::getListBytes, java.time.Clock.systemUTC(),
                this::log, AppVersion.APP);
    }

    private static SiteAuth createSiteAuth(AppDirs dirs, String serversUrl) {
        String origin = siteOrigin(serversUrl);
        if (origin.isEmpty()) {
            return null;
        }
        return new SiteAuth(new HttpSiteApi(origin), SessionStorage.vault(StringVault.system(dirs.siteSessionVaultFile())),
                System::currentTimeMillis, Thread::sleep);
    }

    /** {@code https://host} of the servers URL (the site lives next to the signed list), or empty. */
    static String siteOrigin(String serversUrl) {
        try {
            java.net.URI uri = java.net.URI.create(serversUrl == null ? "" : serversUrl.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "";
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException e) {
            return "";
        }
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
