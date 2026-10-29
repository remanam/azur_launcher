package com.azur.launcher.ui;

import com.azur.launcher.auth.MicrosoftAccount;
import com.azur.launcher.game.LocalVersion;
import com.azur.launcher.game.LocalVersionScanner;
import com.azur.launcher.game.VersionManager;
import com.azur.launcher.i18n.I18n;
import com.azur.launcher.loader.NeoForgeCatalog;
import com.azur.launcher.loader.NeoForgeInstaller;
import com.azur.launcher.model.VersionManifest;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** Home page: pick an installed version, install new ones (vanilla or NeoForge), launch and watch the log. */
final class HomePage {

    private static final String DEFAULT_MC = "1.21.1";

    private final AppContext ctx;
    private final VersionManager versionManager;
    private final VBox root = new VBox(14);

    private final Label heroTitle = UiKit.label("", "hero-title");
    private final Label heroSubtitle = UiKit.label("", "hero-subtitle");
    private final Label greeting = UiKit.label("", "greeting");
    private final ComboBox<LocalVersion> installedBox = new ComboBox<>();
    private final ComboBox<String> mcBox = new ComboBox<>();
    private final ComboBox<String> loaderBox = new ComboBox<>();
    private final Button installButton;
    private final Button launchButton;
    private final TextArea logArea = new TextArea();

    private final PlayController play;
    private volatile boolean busy;

    HomePage(AppContext ctx, PlayController play) {
        this.ctx = ctx;
        this.play = play;
        this.versionManager = new VersionManager(ctx.gamePaths(), ctx::log);

        launchButton = UiKit.button(I18n.tr("home.play"), "icon-play", "launch-button", play::startDefault);
        launchButton.setDefaultButton(true);
        installButton = UiKit.button(I18n.tr("home.install"), "icon-plus", "button-raised", this::install);

        root.getStyleClass().add("page");
        root.getChildren().addAll(greetingBlock(), heroCard(), installedCard(), installCard(), logCard());

        logArea.setText(ctx.logText());
        ctx.bindLog(line -> logArea.appendText(line + "\n"));
        ctx.onAccountsChanged(this::updateHero);
        play.onPhase(this::showPhase);
        refreshInstalled();
        updateHero();
        loadManifest();
    }

    Node node() {
        return UiKit.pageScroll(root);
    }

    // ------------------------------------------------------------------ layout

    private VBox greetingBlock() {
        return new VBox(3, greeting, UiKit.label(I18n.tr("home.greeting_sub"), "greeting-sub"));
    }

    private HBox heroCard() {
        heroSubtitle.setWrapText(true);
        VBox text = new VBox(5, heroTitle, heroSubtitle);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox hero = new HBox(16, UiKit.avatar("A", "hero-avatar", "hero-avatar-text"), text, launchButton);
        hero.getStyleClass().add("hero-card");
        hero.setAlignment(Pos.CENTER_LEFT);
        return hero;
    }

    private VBox installedCard() {
        installedBox.setMaxWidth(Double.MAX_VALUE);
        installedBox.setPromptText(I18n.tr("home.no_versions"));
        installedBox.setCellFactory(list -> new VersionCell());
        installedBox.setButtonCell(new VersionCell());
        installedBox.valueProperty().addListener((obs, old, value) -> updateHero());
        Button folder = UiKit.button(I18n.tr("home.open_folder"), "icon-folder", "button-border",
                () -> ctx.openInSystem(ctx.gameDir()));
        Button refresh = UiKit.button(I18n.tr("home.refresh"), "icon-refresh", "button-border", () -> {
            refreshInstalled();
            loadManifest();
        });
        HBox row = new HBox(8, installedBox, refresh, folder);
        HBox.setHgrow(installedBox, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        return UiKit.card(UiKit.cardHeader("icon-versions", I18n.tr("home.installed")), row);
    }

    private VBox installCard() {
        mcBox.setPrefWidth(160);
        mcBox.valueProperty().addListener((obs, old, value) -> loadLoaders(value));
        loaderBox.setMaxWidth(Double.MAX_VALUE);
        loaderBox.getItems().setAll(I18n.tr("home.loader_vanilla"));
        loaderBox.getSelectionModel().selectFirst();
        HBox row = new HBox(8, mcBox, loaderBox, installButton);
        HBox.setHgrow(loaderBox, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        return UiKit.card(UiKit.cardHeader("icon-java", I18n.tr("home.install_title")),
                UiKit.label(I18n.tr("home.install_hint"), "card-subtitle"), row);
    }

    private VBox logCard() {
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.getStyleClass().add("log-area");
        logArea.setPrefRowCount(9);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        Button clear = UiKit.button(I18n.tr("home.clear_log"), null, "button-text", logArea::clear);
        VBox card = UiKit.card(UiKit.cardHeader("icon-terminal", I18n.tr("home.log"), clear), logArea);
        card.getStyleClass().add("log-card");
        return card;
    }

    // ------------------------------------------------------------------ state

    private void updateHero() {
        MicrosoftAccount account = ctx.accounts.selectedAccount();
        greeting.setText(I18n.tr("home.greeting", account == null ? I18n.tr("home.guest") : account.getUsername()));
        LocalVersion version = installedBox.getValue();
        heroTitle.setText(version == null ? I18n.tr("home.no_version_selected") : version.id());
        heroSubtitle.setText(account == null ? I18n.tr("home.sign_in_needed")
                : I18n.tr("home.playing_as", account.getUsername()));
    }

    /** Re-reads the versions folder (local disk only, so it is cheap) and keeps the selection when possible. */
    private void refreshInstalled() {
        List<LocalVersion> all = new ArrayList<>(LocalVersionScanner.scan(ctx.gameDir()));
        all.removeIf(version -> !version.launchable());
        String wanted = installedBox.getValue() != null ? installedBox.getValue().id() : ctx.config.getVersionId();
        installedBox.getItems().setAll(all);
        for (LocalVersion version : all) {
            if (version.id().equals(wanted)) {
                installedBox.setValue(version);
                return;
            }
        }
        if (!all.isEmpty()) {
            installedBox.setValue(all.get(all.size() - 1));
        }
    }

    private void loadManifest() {
        ctx.io.submit(() -> {
            try {
                VersionManifest manifest = versionManager.getManifest(false);
                List<String> releases = manifest.getVersions().stream()
                        .filter(entry -> "release".equals(entry.getType()))
                        .map(VersionManifest.VersionEntry::getId).toList();
                Platform.runLater(() -> {
                    mcBox.getItems().setAll(releases);
                    mcBox.setValue(releases.contains(DEFAULT_MC) ? DEFAULT_MC : releases.get(0));
                });
            } catch (Exception e) {
                ctx.log("Version list unavailable: " + e.getMessage());
                ctx.setStatus(I18n.tr("home.versions_unavailable"));
            }
        });
    }

    private void loadLoaders(String mcVersion) {
        loaderBox.getItems().setAll(I18n.tr("home.loader_vanilla"));
        loaderBox.getSelectionModel().selectFirst();
        if (mcVersion == null || NeoForgeCatalog.versionPrefix(mcVersion) == null) {
            return;
        }
        ctx.io.submit(() -> {
            try {
                List<String> builds = NeoForgeCatalog.versions(mcVersion);
                Platform.runLater(() -> {
                    if (mcVersion.equals(mcBox.getValue())) {
                        loaderBox.getItems().addAll(builds.stream().map(v -> "NeoForge " + v).toList());
                    }
                });
            } catch (Exception e) {
                ctx.log("NeoForge list unavailable: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ install

    private void install() {
        String mc = mcBox.getValue();
        int loaderIndex = loaderBox.getSelectionModel().getSelectedIndex();
        if (mc == null || busy) {
            return;
        }
        String neoForge = loaderIndex > 0 ? loaderBox.getValue().substring("NeoForge ".length()) : null;
        setBusy(true);
        ctx.setStatus(I18n.tr("home.installing"));
        ctx.io.submit(() -> {
            String installedId = mc;
            try {
                versionManager.getVersionMeta(mc, false);
                if (neoForge != null) {
                    NeoForgeInstaller.InstallResult result =
                            new NeoForgeInstaller(ctx.dirs, ctx::log).install(mc, neoForge, ctx.gameDir());
                    ctx.log(result.message());
                    if (!result.success()) {
                        Platform.runLater(() -> error(result.message()));
                        return;
                    }
                    installedId = result.versionId();
                }
                String selectId = installedId;
                ctx.config.setVersionId(selectId);
                Platform.runLater(this::refreshInstalled);
                ctx.setStatus(I18n.tr("home.install_done", selectId));
            } catch (Exception e) {
                ctx.log("Install failed: " + e.getMessage());
                Platform.runLater(() -> error(e.getMessage()));
            } finally {
                Platform.runLater(() -> setBusy(false));
            }
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        installButton.setDisable(value);
        launchButton.setDisable(value || play.phase() == PlayController.Phase.PREPARING);
    }

    // ------------------------------------------------------------------ launch

    /** Mirrors the shared play state on the button; the launch itself is {@link PlayController}. */
    private void showPhase(PlayController.Phase phase) {
        boolean running = phase == PlayController.Phase.RUNNING;
        boolean preparing = phase == PlayController.Phase.PREPARING;
        launchButton.setDisable(preparing);
        launchButton.setText(I18n.tr(running ? "home.stop" : "home.play"));
        launchButton.getStyleClass().remove("stopping");
        if (running) {
            launchButton.getStyleClass().add("stopping");
        }
        installButton.setDisable(phase != PlayController.Phase.IDLE || busy);
    }

    private void error(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(ctx.stage);
        alert.setTitle(I18n.tr("common.error"));
        alert.setHeaderText(I18n.tr("common.error"));
        alert.setContentText(message);
        UiKit.styleDialog(alert.getDialogPane(), ctx.config.isDarkMode());
        alert.showAndWait();
    }

    /** One row in the installed-version combo. */
    private static final class VersionCell extends ListCell<LocalVersion> {
        @Override
        protected void updateItem(LocalVersion version, boolean empty) {
            super.updateItem(version, empty);
            setText(empty || version == null ? null : version.id());
        }
    }
}
