package com.azur.launcher.ui;

import com.azur.launcher.auth.AuthAccount;
import com.azur.launcher.auth.OfflineAccount;
import com.azur.launcher.i18n.I18n;
import com.azur.launcher.launch.LaunchRequest;
import com.azur.launcher.net.HttpUtils;
import com.azur.launcher.profile.Instance;
import com.azur.launcher.profile.InstanceStore;
import com.azur.launcher.profile.Profile;
import com.azur.launcher.profile.ServerProfiles;
import com.azur.launcher.servers.ServerEntry;
import com.azur.launcher.servers.ServerListService;
import com.azur.launcher.servers.ServerPing;
import com.azur.launcher.servers.ServerStatusService;
import com.azur.launcher.sync.PackSyncService;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Clock;

/**
 * Home = the server screen: logo, nickname, server with status, one pack line and ONE big button whose label follows
 * the state (Start / Update / Updating N% / Play / Retry). A failure is one sentence with an expandable detail.
 */
final class HomePage {

    private static final long PING_INTERVAL_MS = 15_000;

    private final AppContext ctx;
    private final PlayController play;
    private final PackState packState;
    private final Runnable openAccounts;
    private final ServerStatusService status;
    private final StackPane root = new StackPane();
    private final VBox main = new VBox(24);
    private final Label nickLabel = UiKit.label("", "home-sub");
    private final Label offlineTag = UiKit.label("", "tag");
    private final Button signInLink;
    private final Region dot = new Region();
    private final Label serverLabel = UiKit.label("", "home-title");
    private final Label statusLabel = UiKit.label("", "home-sub");
    private final Label packLabel = UiKit.label("", "home-sub");
    private final Label bannerLabel = UiKit.label("", "home-sub");
    private final Button mainButton;
    private final Label errorLabel = UiKit.label("", "home-error");
    private final Label detailsLabel = UiKit.label("", "home-error-details");
    private final TitledPane details = new TitledPane(I18n.tr("home.error.details"), detailsLabel);
    private final VBox errorBox = new VBox(8, errorLabel, details);
    private final FirstRunWizard wizard;

    private ServerEntry server;
    private boolean installed;
    private boolean updateAvailable;
    private boolean updating;
    private boolean failed;
    private int percent = -1;
    private boolean wizardActive;
    private final Timeline refresher = new Timeline(
            new KeyFrame(Duration.millis(PackState.TTL_MS), event -> refreshState(false)));

    HomePage(AppContext ctx, PlayController play, PackState packState, Runnable openAccounts) {
        this.ctx = ctx;
        this.play = play;
        this.packState = packState;
        this.openAccounts = openAccounts;
        this.status = new ServerStatusService(ctx.io, PING_INTERVAL_MS);
        mainButton = UiKit.button("", null, "launch-button", this::onMainButton);
        mainButton.setDefaultButton(true);
        signInLink = UiKit.button(I18n.tr("home.sign_in_ms"), null, "button-text", openAccounts);
        wizard = new FirstRunWizard(ctx, openAccounts, this::install, this::playFromWizard);
        wizardActive = HomeState.wizardNeeded(ctx.accounts.selected() != null);

        dot.getStyleClass().add("status-dot");
        details.setExpanded(false);
        details.getStyleClass().add("advanced-pane");
        errorLabel.setWrapText(true);
        detailsLabel.setWrapText(true);
        errorBox.setMaxWidth(520);
        bannerLabel.setWrapText(true);
        bannerLabel.setMaxWidth(520);
        showBanner("");
        showError(null, null);

        Region logo = new Region();
        logo.getStyleClass().add("home-logo");
        HBox nickRow = new HBox(12, nickLabel, offlineTag, signInLink);
        nickRow.setAlignment(Pos.CENTER);
        HBox serverRow = new HBox(12, dot, serverLabel);
        serverRow.setAlignment(Pos.CENTER);
        main.getChildren().addAll(logo, nickRow, serverRow, statusLabel, packLabel, bannerLabel, mainButton, errorBox);
        main.setAlignment(Pos.TOP_CENTER);
        main.getStyleClass().add("home-hero");
        root.setAlignment(Pos.TOP_CENTER);

        ctx.onAccountsChanged(this::renderAccount);
        play.onPhase(phase -> render());
        renderAccount();
        loadServer();
        refresher.setCycleCount(Timeline.INDEFINITE);
        refresher.play();
    }

    /** The Home page became visible: re-read the state (the pack answer is cached, so this is cheap). */
    void onShown() {
        refreshState(false);
    }

    Node node() {
        return UiKit.pageScroll(root);
    }

    // ------------------------------------------------------------------ data

    private void loadServer() {
        ctx.io.submit(() -> {
            ServerEntry first = null;
            try {
                ServerListService service = new ServerListService(ctx.dirs.serversCacheFile(),
                        ServerListService.loadKey(ctx.dirs.serversKeyFile()), ctx.pack.getServersUrl(),
                        HttpUtils::getBytes, Clock.systemUTC(), ctx::log);
                first = service.load().list().servers().stream().findFirst().orElse(null);
            } catch (Exception e) {
                ctx.log("Home: server list unavailable: " + e.getMessage());
            }
            ServerEntry entry = first;
            Platform.runLater(() -> {
                server = entry;
                play.setServer(entry);
                refreshState(false);
            });
        });
    }

    /** Re-reads installed / update state off the UI thread, then redraws. */
    private void refreshState(boolean force) {
        if (updating) {
            return;
        }
        ServerEntry entry = server;
        ctx.io.submit(() -> {
            PackState.Snapshot snap = new PackState.Snapshot(false, false, "");
            try {
                snap = packState.read(instance(), entry, force);
            } catch (Exception e) {
                ctx.log("Home: state unavailable: " + e.getMessage());
            }
            PackState.Snapshot shot = snap;
            Platform.runLater(() -> {
                if (updating) {
                    return;
                }
                installed = shot.installed();
                updateAvailable = shot.updateAvailable();
                showBanner(shot.banner());
                renderServer();
                render();
                if (server != null) {
                    status.request(server, result -> Platform.runLater(() -> showPing(result)));
                }
            });
        });
    }

    private Instance instance() throws java.io.IOException {
        InstanceStore store = new InstanceStore(ctx.dirs);
        return server == null ? store.loadDefault()
                : store.ensure(ServerProfiles.derive(server, InstanceStore.bundledProfile()));
    }

    // ------------------------------------------------------------------ actions

    private void onMainButton() {
        switch (currentAction()) {
            case PLAY -> startGame();
            case START, UPDATE, RETRY -> install();
            case UPDATING -> { }
        }
    }

    private void startGame() {
        if (server == null) {
            play.startDefault();
        } else {
            play.start(new LaunchRequest(server));
        }
    }

    private void playFromWizard() {
        wizardActive = false;
        render();
        startGame();
    }

    /** Installs the game and the pack (or updates it), reporting percent on the button. */
    private void install() {
        if (updating) {
            return;
        }
        updating = true;
        failed = false;
        percent = -1;
        showError(null, null);
        render();
        ServerEntry entry = server;
        ctx.io.submit(() -> {
            PackOps.Outcome outcome = PackOps.download(ctx, entry,
                    (phase, file, done, total) -> progress(done, total),
                    (done, total, file) -> progress(done, total));
            Platform.runLater(() -> finishInstall(outcome));
        });
    }

    private void progress(long done, long total) {
        int value = HomeState.percent(done, total);
        Platform.runLater(() -> {
            if (value != percent) {
                percent = value;
                render();
            }
        });
    }

    private void finishInstall(PackOps.Outcome outcome) {
        updating = false;
        PackSyncService.Result result = outcome.result();
        if (result.ok()) {
            packState.invalidate();
            installed = true;
            updateAvailable = false;
            showError(null, null);
            if (wizardActive) {
                wizard.showStep(3);
            }
            render();
            return;
        }
        failed = true;
        String key = "sync.err." + result.code();
        String reason = I18n.tr(key).equals(key) ? result.message() : I18n.tr(key);
        ctx.log("Home install failed: exit " + result.exit() + " " + result.code() + " " + result.message());
        showError(I18n.tr("sync.failed", reason), result.code() + ": " + result.message());
        render();
    }

    // ------------------------------------------------------------------ rendering

    private HomeState.Action currentAction() {
        return HomeState.action(installed, updateAvailable, updating, failed);
    }

    private String buttonText() {
        PlayController.Phase phase = play.phase();
        if (phase == PlayController.Phase.RUNNING) {
            return I18n.tr("home.stop");
        }
        if (phase == PlayController.Phase.PREPARING) {
            return I18n.tr("home.preparing");
        }
        HomeState.Action action = currentAction();
        if (action == HomeState.Action.UPDATING) {
            return percent < 0 ? I18n.tr("home.btn.updating_nop") : I18n.tr(action.labelKey(), percent);
        }
        return I18n.tr(action.labelKey());
    }

    private void render() {
        PlayController.Phase phase = play.phase();
        String text = buttonText();
        mainButton.setText(text);
        mainButton.setDisable(currentAction() == HomeState.Action.UPDATING || phase == PlayController.Phase.PREPARING);
        mainButton.getStyleClass().remove("stopping");
        if (phase == PlayController.Phase.RUNNING) {
            mainButton.getStyleClass().add("stopping");
        }
        wizard.showInstall(text, updating, failed ? errorLabel.getText() : null);
        root.getChildren().setAll(wizardActive ? wizard.node() : main);
    }

    private void renderAccount() {
        AuthAccount account = ctx.accounts.selected();
        if (account == null) {
            wizardActive = true;
            wizard.showStep(1);
        } else if (wizardActive && wizard.step() == 1) {
            wizard.showStep(2);
        }
        nickLabel.setText(account == null ? "" : I18n.tr("home.player", account.getUsername()));
        boolean offline = account instanceof OfflineAccount;
        offlineTag.setText(I18n.tr("home.offline_tag"));
        offlineTag.setVisible(offline);
        offlineTag.setManaged(offline);
        signInLink.setVisible(offline);
        signInLink.setManaged(offline);
        render();
    }

    private void renderServer() {
        serverLabel.setText(server == null ? I18n.tr("home.default_server") : server.name());
        if (server == null) {
            statusLabel.setText("");
        } else {
            statusLabel.setText(I18n.tr("servers.checking"));
        }
        try {
            Profile pin = server == null ? InstanceStore.bundledProfile()
                    : ServerProfiles.derive(server, InstanceStore.bundledProfile());
            packLabel.setText(I18n.tr("home.pack_line", pin.minecraft(), pin.neoforge()));
        } catch (Exception e) {
            packLabel.setText("");
        }
    }

    private void showPing(ServerPing.Result result) {
        dot.getStyleClass().removeAll("online", "down");
        dot.getStyleClass().add(result.online() ? "online" : "down");
        statusLabel.setText(result.online()
                ? I18n.tr("servers.online", result.playersOnline(), result.playersMax(), result.latencyMs())
                : I18n.tr("servers.down"));
    }

    private void showBanner(String text) {
        boolean has = !text.isEmpty();
        bannerLabel.setText(has ? I18n.tr("home.maintenance", text) : "");
        bannerLabel.setVisible(has);
        bannerLabel.setManaged(has);
    }

    private void showError(String sentence, String detail) {
        boolean has = sentence != null;
        errorBox.setVisible(has);
        errorBox.setManaged(has);
        errorLabel.setText(has ? sentence : "");
        detailsLabel.setText(detail == null ? "" : detail);
        details.setExpanded(false);
    }
}
