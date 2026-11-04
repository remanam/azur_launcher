package com.azur.launcher.ui;

import com.azur.launcher.auth.AuthAccount;
import com.azur.launcher.i18n.I18n;
import com.azur.launcher.update.UpdateService;
import com.azur.launcher.launch.LaunchRequest;
import com.azur.launcher.net.HttpUtils;
import com.azur.launcher.profile.Instance;
import com.azur.launcher.profile.InstanceStore;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Clock;
import com.azur.launcher.sync.PackSyncService;
import java.util.List;

/**
 * Home = the only player screen (forge v3 layout): header (bell, nickname, gear), notice banner, a card with the server plate,
 * pack status and the server text next to the hero picture, the action bar (status or install progress, ONE big Play button)
 * and the news line. A failure is one sentence with an expandable detail. While no nickname exists the card holds the first-run wizard.
 */
final class HomePage {

    private static final String SITE_URL = "https://example.test";

    private static final long PING_INTERVAL_MS = 15_000;

    private final AppContext ctx;
    private final PlayController play;
    private final PackState packState;
    private final ServerStatusService status;
    private final StackPane root = new StackPane();
    private final VBox card = new VBox(14);
    private final VBox info = new VBox(14);
    private final Label nickLabel = UiKit.label("", "nick-name");
    private final Label nickSub = UiKit.label("", "nick-sub");
    private final StackPane avatarHolder = new StackPane();
    private final Region dot = new Region();
    private final Label statusLabel = UiKit.label("", "home-sub");
    private final Label bannerLabel = UiKit.label("", "home-sub");
    private final Label packTitle = UiKit.label("", "pack-title");
    private final Label packTag = UiKit.label("", "pack-tag");
    private final Label aboutHead = UiKit.label(I18n.tr("home.about_server"), "section-head");
    private final Label aboutText = UiKit.label("", "home-sub");
    private final Region statusIcon = Icons.of("icon-check", 18);
    private final Label statusTitle = UiKit.label("", "status-title");
    private final Label statusHint = UiKit.label("", "status-hint");
    private final VBox statusBox = new VBox(4, new HBox(8, statusIcon, statusTitle), statusHint);
    private final Button mainButton;
    private final Label updateLabel = UiKit.label("", "home-sub");
    private final Button updateButton = UiKit.button("", null, "launch-button", () -> { });
    private final VBox updateBox = new VBox(8, updateLabel, updateButton);
    private boolean updateRequired;
    private final FeedPanel feed;
    private final SelectableText errorLabel = UiKit.selectable("", null);
    private final SelectableText detailsLabel = UiKit.selectable("", "home-error-details");
    private final TitledPane details = new TitledPane(I18n.tr("home.error.details"), detailsLabel);
    private final Button copyError = UiKit.copyButton(this::errorText);
    private final VBox errorBox = new VBox(8, errorLabel, details, copyError);
    private final InstallProgress installProgress;
    private final InstallProgressView progressView = new InstallProgressView();
    private final Timeline progressTicker = new Timeline(new KeyFrame(Duration.millis(100), e -> tick()));
    private final FirstRunWizard wizard;
    private final Runnable openAdvanced;
    private String packVersion = "";

    private final ComboBox<ServerEntry> serverBox = new ComboBox<>();
    private boolean fillingBox;
    private ServerEntry server;
    private boolean installed;
    private boolean updateAvailable;
    private boolean updating;
    private boolean failed;
    private InstallProgress.Step shownStep;
    private boolean wizardActive;
    private final Timeline refresher = new Timeline(
            new KeyFrame(Duration.millis(PackState.TTL_MS), event -> refreshState(false)));

    HomePage(AppContext ctx, PlayController play, PackState packState, Runnable openAdvanced) {
        this.ctx = ctx;
        this.openAdvanced = openAdvanced;
        this.play = play;
        this.packState = packState;
        this.installProgress = play.installProgress();
        this.feed = new FeedPanel(ctx, new com.azur.launcher.feed.FeedState(ctx.dirs.root().resolve("feed_state.txt")),
                () -> server == null ? "" : server.id(), this::render);
        this.status = new ServerStatusService(ctx.io, PING_INTERVAL_MS);
        mainButton = UiKit.button("", null, "launch-button", this::onMainButton);
        mainButton.setDefaultButton(true);
        wizard = new FirstRunWizard(ctx, this::install, this::playFromWizard);
        wizardActive = HomeState.wizardNeeded(ctx.accounts.selected() != null);

        dot.getStyleClass().add("status-dot");
        serverBox.getStyleClass().add("server-chip");
        serverBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ServerEntry entry) {
                return entry == null ? "" : entry.name();
            }

            @Override
            public ServerEntry fromString(String text) {
                return null;
            }
        });
        serverBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ServerEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(null);
                HBox chip = new HBox(8, UiKit.label(I18n.tr("home.server"), "chip-key"),
                        UiKit.label(entry == null ? I18n.tr("home.default_server") : entry.name(), "chip-name"));
                chip.setAlignment(Pos.CENTER_LEFT);
                setGraphic(chip);
            }
        });
        serverBox.setOnAction(e -> {
            ServerEntry chosen = serverBox.getValue();
            if (!fillingBox && chosen != null && server != null && !chosen.id().equals(server.id())) {
                ctx.config.setLastServerId(chosen.id());
                ctx.saveConfig();
                applyServer(chosen, null, null);
            }
        });
        showServerBox(List.of());
        details.setExpanded(false);
        details.getStyleClass().add("advanced-pane");
        errorBox.getStyleClass().add("home-error");
        progressTicker.setCycleCount(Timeline.INDEFINITE);
        bannerLabel.setWrapText(true);
        aboutText.setWrapText(true);
        statusHint.setWrapText(true);
        showBanner("");
        showError(null, null);
        updateLabel.setWrapText(true);
        updateBox.setAlignment(Pos.CENTER_LEFT);
        updateBox.setVisible(false);
        updateBox.setManaged(false);
        packTag.getStyleClass().add("tag-ok");

        HBox serverRow = new HBox(14, serverBox, UiKit.spacer(), dot, statusLabel);
        serverRow.setAlignment(Pos.CENTER_LEFT);
        HBox packRow = new HBox(12, packTitle, packTag);
        packRow.setAlignment(Pos.CENTER_LEFT);
        info.getChildren().addAll(serverRow, packRow, bannerLabel, aboutHead, aboutText, updateBox, errorBox);
        card.getStyleClass().add("forge-card");
        HBox.setHgrow(card, Priority.ALWAYS);

        Region hero = new Region();
        hero.getStyleClass().add("hero-art");
        hero.setMinWidth(300);
        hero.setPrefWidth(340);
        hero.setMaxWidth(340);
        HBox middle = new HBox(16, card, hero);
        middle.getStyleClass().add("home-middle");

        Button gear = UiKit.button("", null, "gear-button", openAdvanced);
        gear.setGraphic(Icons.of("icon-gear", 20));
        gear.setTooltip(new Tooltip(I18n.tr("home.advanced")));
        avatarHolder.getStyleClass().add("nick-avatar");
        VBox nickText = new VBox(1, nickLabel, nickSub);
        HBox nickChip = new HBox(10, avatarHolder, nickText);
        nickChip.getStyleClass().add("nick-chip");
        nickChip.setAlignment(Pos.CENTER_LEFT);
        HBox header = new HBox(14, UiKit.spacer(), feed.bell(), nickChip, gear);
        header.getStyleClass().add("home-header");
        header.setAlignment(Pos.CENTER_RIGHT);
        VBox top = new VBox(header, feed.banner());

        Region copperLine = new Region();
        copperLine.getStyleClass().add("copper-line");
        progressView.node().visibleProperty().addListener(o -> syncStatusSlot());
        StackPane slot = new StackPane(statusBox, progressView.node());
        slot.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slot, Priority.ALWAYS);
        HBox actions = new HBox(24, slot, mainButton);
        actions.getStyleClass().add("action-bar");
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox bottom = new VBox(copperLine, actions, feed.news());

        BorderPane screen = new BorderPane();
        screen.setTop(top);
        screen.setCenter(middle);
        screen.setBottom(bottom);
        Node history = feed.history();
        StackPane.setAlignment(history, Pos.TOP_RIGHT);
        StackPane.setMargin(history, new javafx.geometry.Insets(54, 190, 0, 0));
        root.getChildren().addAll(screen, history);
        syncStatusSlot();

        ctx.onAccountsChanged(this::renderAccount);
        play.onPhase(this::onPhase);
        renderAccount();
        loadServer();
        ctx.stage.focusedProperty().addListener((o, was, focused) -> {
            if (focused) {
                refreshState(true);
            }
        });
        refresher.setCycleCount(Timeline.INDEFINITE);
        refresher.play();
    }

    /**
     * Launcher self-update notice (JavaFX thread). READY: version + "Update and restart" (a required update also
     * blocks Play); WRAPPER_OLD: link to the site, nothing is applied.
     */
    void showUpdate(UpdateService.Status status, Runnable restart) {
        UpdateService.Kind kind = status.kind();
        boolean ready = kind == UpdateService.Kind.READY;
        boolean old = kind == UpdateService.Kind.WRAPPER_OLD;
        updateRequired = (ready || old) && status.manifest().required();
        if (ready) {
            String version = status.manifest().version();
            updateLabel.setText(updateRequired ? I18n.tr("update.required", version) : I18n.tr("update.available", version));
            updateButton.setText(I18n.tr("update.button"));
            updateButton.setOnAction(event -> restart.run());
        } else if (old) {
            updateLabel.setText(I18n.tr("update.wrapper_old", status.manifest().minWrapper()));
            updateButton.setText(I18n.tr("update.site"));
            updateButton.setOnAction(event -> ctx.host.showDocument(SITE_URL));
        }
        updateBox.setVisible(ready || old);
        updateBox.setManaged(ready || old);
        render();
    }

    /** The notice feed changed (JavaFX thread). */
    void showFeed(com.azur.launcher.feed.FeedDocument doc) {
        feed.show(doc);
    }

    /** The Home page became visible: ask the server again (the button label must not show a stale answer). */
    void onShown() {
        refreshState(true);
    }

    Node node() {
        return root;
    }

    // ------------------------------------------------------------------ data

    private void loadServer() {
        play.setServerLoading();
        ctx.io.submit(() -> {
            ServerListService service;
            try {
                service = new ServerListService(ctx.dirs.serversCacheFile(),
                        ServerListService.appKey(ctx.dirs.serversKeyFile()), ctx.pack.getServersUrl(),
                        HttpUtils::getListBytes, Clock.systemUTC(), ctx::log);
            } catch (Exception e) {
                ctx.log("Server list: cannot start: " + e);
                applyServer(null, null, "key: " + e.getMessage());
                return;
            }
            ServerListService.Loaded cached = service.cached();
            if (cached != null) {
                applyServer(pick(cached), cached.list().servers(), null);
            }
            try {
                ServerListService.Loaded loaded = service.load();
                if (loaded.source() != ServerListService.Source.NETWORK) {
                    ctx.log("Server list from cache (server unavailable)");
                }
                applyServer(pick(loaded), loaded.list().servers(), null);
            } catch (Exception e) {
                if (cached != null) {
                    applyServer(pick(cached), cached.list().servers(), e.getMessage());
                    return;
                }
                ctx.log("Server list: using the built-in default server: " + e.getMessage());
                ctx.setStatus(I18n.tr("servers.fallback", e.getMessage()));
                applyServer(com.azur.launcher.servers.BuiltinServers.isles(),
                        com.azur.launcher.servers.BuiltinServers.all(), null);
            }
        });
    }

    private ServerEntry pick(ServerListService.Loaded loaded) {
        return com.azur.launcher.servers.ServerChoice.pick(loaded.list().servers(), ctx.config.getLastServerId());
    }

    private void applyServer(ServerEntry entry, List<ServerEntry> all, String problem) {
        Platform.runLater(() -> {
            server = entry;
            if (all != null) {
                showServerBox(all);
                fillingBox = true;
                serverBox.setValue(entry);
                fillingBox = false;
            }
            play.setServer(entry, entry == null ? problem : null);
            feed.refresh();
            refreshState(false);
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
            String version = "";
            try {
                Instance current = instance();
                snap = packState.read(current, entry, force);
                version = PackSyncService.standard(ctx.dirs).installedVersion(current.gameDir());
            } catch (Exception e) {
                ctx.log("Home: state unavailable: " + e.getMessage());
            }
            PackState.Snapshot shot = snap;
            String shownVersion = version == null ? "" : version;
            Platform.runLater(() -> {
                if (updating) {
                    return;
                }
                packVersion = shownVersion;
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
                    render();
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
        boolean loginStep = wizardActive && wizard.step() == 1;
        mainButton.setDisable(loginStep || updateRequired || feed.blocksPlay() && phase == PlayController.Phase.IDLE);
        mainButton.getStyleClass().remove("stopping");
        if (phase == PlayController.Phase.RUNNING) {
            mainButton.getStyleClass().add("stopping");
        }
        wizard.showInstall(text, updating, failed ? errorLabel.getText() : null);
        card.getChildren().setAll(wizardActive ? wizard.node() : info);
        renderPack();
        renderStatus();
    }

    /** Pack row of the card: «Сборка 1.4.3» and a tag (up to date / update / install). */
    private void renderPack() {
        boolean known = installed && !packVersion.isEmpty();
        packTitle.setText(known ? I18n.tr("home.pack_title", packVersion)
                : I18n.tr(installed ? "home.pack_title_plain" : "home.pack_none"));
        String key = !installed ? "home.pack_install" : updateAvailable ? "home.pack_update" : "home.pack_ok";
        packTag.setText(I18n.tr(key));
        packTag.getStyleClass().removeAll("tag-ok", "tag-warn");
        packTag.getStyleClass().add(installed && !updateAvailable ? "tag-ok" : "tag-warn");
    }

    private void renderStatus() {
        HomeState.Status status = HomeState.status(ctx.accounts.selected() != null, installed, updateAvailable);
        statusTitle.setText(I18n.tr(status.titleKey()));
        statusTitle.getStyleClass().removeAll("ok", "warn");
        statusTitle.getStyleClass().add(status.styleClass());
        statusIcon.getStyleClass().removeAll("icon-check", "icon-lock", "icon-update");
        statusIcon.getStyleClass().add(status == HomeState.Status.READY ? "icon-check"
                : status == HomeState.Status.LOGIN ? "icon-lock" : "icon-update");
        statusIcon.getStyleClass().removeAll("ok", "warn");
        statusIcon.getStyleClass().add(status.styleClass());
        if (status == HomeState.Status.READY && server != null) {
            statusHint.setText(I18n.tr("home.status_info", server.mcVersion(), server.neoforge(),
                    HomeState.gigabytes(ctx.config.getMaxMemoryMb())));
        } else {
            statusHint.setText(I18n.tr(status.hintKey()));
        }
    }

    /** The install progress replaces the status text in the same slot while it is visible. */
    private void syncStatusSlot() {
        boolean progress = progressView.node().isVisible();
        statusBox.setVisible(!progress);
        statusBox.setManaged(!progress);
    }

    private void renderAccount() {
        AuthAccount account = ctx.accounts.selected();
        if (account == null) {
            wizardActive = true;
            wizard.showStep(1);
        } else if (wizardActive && wizard.step() == 1) {
            wizard.showStep(2);
        }
        nickLabel.setText(account == null ? I18n.tr("home.no_nick") : account.getUsername());
        nickSub.setText(I18n.tr(account == null ? "home.sign_in_hint" : "home.account"));
        avatarHolder.getChildren().setAll(UiKit.label(account == null ? "?" : account.getUsername().substring(0, 1).toUpperCase(),
                "nick-avatar-text"));
        render();
    }

    private void renderServer() {
        aboutText.setText(server == null ? "" : server.motd() == null ? "" : server.motd());
        boolean hasAbout = !aboutText.getText().isBlank();
        aboutHead.setVisible(hasAbout);
        aboutHead.setManaged(hasAbout);
        aboutText.setVisible(hasAbout);
        aboutText.setManaged(hasAbout);
        statusLabel.setText(server == null ? "" : I18n.tr("servers.checking"));
    }

    private void showPing(ServerPing.Result result) {
        dot.getStyleClass().removeAll("online", "down");
        dot.getStyleClass().add(result.online() ? "online" : "down");
        statusLabel.setText(result.online()
                ? I18n.tr("servers.online", result.playersOnline(), result.playersMax(), result.latencyMs())
                : I18n.tr("servers.down"));
    }

    /** The plate is always there; it opens a list only when the signed list has 2+ servers (usually it is just Azur). */
    private void showServerBox(List<ServerEntry> all) {
        boolean selectable = com.azur.launcher.servers.ServerChoice.selectorVisible(all);
        fillingBox = true;
        serverBox.getItems().setAll(all);
        fillingBox = false;
        serverBox.setMouseTransparent(!selectable);
        serverBox.setFocusTraversable(selectable);
        serverBox.getStyleClass().remove("single");
        if (!selectable) {
            serverBox.getStyleClass().add("single");
        }
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
