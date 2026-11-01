package com.azur.launcher.ui;

import com.azur.launcher.auth.AuthAccount;
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

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
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
 * the state (Start / Update / Installing / Updating / Checking / Play / Retry). A failure is one sentence with an expandable detail.
 */
final class HomePage {

    private static final long PING_INTERVAL_MS = 15_000;

    private final AppContext ctx;
    private final PlayController play;
    private final PackState packState;
    private final ServerStatusService status;
    private final StackPane root = new StackPane();
    private final VBox main = new VBox(24);
    private final Label nickLabel = UiKit.label("", "home-sub");
    private final Region dot = new Region();
    private final Label serverLabel = UiKit.label("", "home-title");
    private final Label statusLabel = UiKit.label("", "home-sub");
    private final Label packLabel = UiKit.label("", "home-sub");
    private final Label bannerLabel = UiKit.label("", "home-sub");
    private final Button mainButton;
    private final SelectableText errorLabel = UiKit.selectable("", null);
    private final SelectableText detailsLabel = UiKit.selectable("", "home-error-details");
    private final TitledPane details = new TitledPane(I18n.tr("home.error.details"), detailsLabel);
    private final Button copyError = UiKit.copyButton(this::errorText);
    private final VBox errorBox = new VBox(8, errorLabel, details, copyError);
    private final InstallProgress installProgress;
    private final InstallProgressView progressView = new InstallProgressView();
    private final Timeline progressTicker = new Timeline(new KeyFrame(Duration.millis(100), e -> tick()));
    private final FirstRunWizard wizard;

    private ServerEntry server;
    private boolean installed;
    private boolean updateAvailable;
    private boolean updating;
    private boolean failed;
    private InstallProgress.Step shownStep;
    private boolean wizardActive;
    private final Timeline refresher = new Timeline(
            new KeyFrame(Duration.millis(PackState.TTL_MS), event -> refreshState(false)));

    HomePage(AppContext ctx, PlayController play, PackState packState) {
        this.ctx = ctx;
        this.play = play;
        this.packState = packState;
        this.installProgress = play.installProgress();
        this.status = new ServerStatusService(ctx.io, PING_INTERVAL_MS);
        mainButton = UiKit.button("", null, "launch-button", this::onMainButton);
        mainButton.setDefaultButton(true);
        wizard = new FirstRunWizard(ctx, this::install, this::playFromWizard);
        wizardActive = HomeState.wizardNeeded(ctx.accounts.selected() != null);

        dot.getStyleClass().add("status-dot");
        details.setExpanded(false);
        details.getStyleClass().add("advanced-pane");
        errorBox.getStyleClass().add("home-error");
        errorBox.setMaxWidth(520);
        progressTicker.setCycleCount(Timeline.INDEFINITE);
        bannerLabel.setWrapText(true);
        bannerLabel.setMaxWidth(520);
        showBanner("");
        showError(null, null);

        Region logo = new Region();
        logo.getStyleClass().add("home-logo");
        HBox nickRow = new HBox(12, nickLabel);
        nickRow.setAlignment(Pos.CENTER);
        HBox serverRow = new HBox(12, dot, serverLabel);
        serverRow.setAlignment(Pos.CENTER);
        main.getChildren().addAll(logo, nickRow, serverRow, statusLabel, packLabel, bannerLabel, mainButton, progressView.node(), errorBox);
        main.setAlignment(Pos.TOP_CENTER);
        main.getStyleClass().add("home-hero");
        root.setAlignment(Pos.TOP_CENTER);

        ctx.onAccountsChanged(this::renderAccount);
        play.onPhase(this::onPhase);
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
                        ServerListService.appKey(ctx.dirs.serversKeyFile()), ctx.pack.getServersUrl(),
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

    /** One button: Install and play / Update and play / Play / Retry all run the same flow (game, pack, launch). */
    private void onMainButton() {
        startGame();
    }

    private void startGame() {
        failed = false;
        showError(null, null);
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

    /** The wizard's install step is the same Play flow. */
    private void install() {
        startGame();
    }

    private void onPhase(PlayController.Phase phase) {
        boolean busy = phase == PlayController.Phase.PREPARING;
        if (busy && !updating) {
            updating = true;
            progressView.show(installProgress.snapshot());
            progressTicker.play();
        } else if (!busy && updating) {
            updating = false;
            boolean success = phase == PlayController.Phase.RUNNING;
            stopProgress(success);
            packState.invalidate();
            refreshState(true);
        }
        render();
    }

    /** At most 10 redraws per second: the workers only write into the model, this timer reads it. */
    private void tick() {
        InstallProgress.Step before = shownStep;
        InstallProgress.Snapshot snapshot = installProgress.snapshot();
        progressView.show(snapshot);
        wizard.progress().show(snapshot);
        shownStep = snapshot.step();
        if (before != shownStep) {
            render();
        }
    }

    private void stopProgress(boolean success) {
        progressTicker.stop();
        shownStep = null;
        if (success) {
            installProgress.finish();
            InstallProgress.Snapshot done = installProgress.snapshot();
            progressView.show(done);
            wizard.progress().show(done);
            PauseTransition hide = new PauseTransition(Duration.seconds(3));
            hide.setOnFinished(e -> {
                if (!updating) {
                    progressView.hide();
                    wizard.progress().hide();
                }
            });
            hide.play();
        } else {
            progressView.hide();
            wizard.progress().hide();
        }
    }

    private String errorText() {
        String detail = detailsLabel.getText();
        return detail.isEmpty() ? errorLabel.getText() : errorLabel.getText() + "\n" + detail;
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
            return I18n.tr("home.cancel");
        }
        HomeState.Action action = currentAction();
        if (action == HomeState.Action.UPDATING) {
            return I18n.tr(HomeState.busyLabelKey(installProgress.step() == InstallProgress.Step.CHECK,
                    installProgress.fresh()));
        }
        return I18n.tr(action.labelKey());
    }

    private void render() {
        PlayController.Phase phase = play.phase();
        String text = buttonText();
        mainButton.setText(text);
        mainButton.setDisable(false);
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
        errorLabel.show(has ? sentence : "");
        detailsLabel.show(detail);
        details.setVisible(detail != null && !detail.isEmpty());
        details.setManaged(details.isVisible());
        details.setExpanded(false);
    }
}
