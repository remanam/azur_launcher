package com.azur.launcher.ui;

import com.azur.launcher.auth.AuthAccount;
import com.azur.launcher.game.LocalVersion;
import com.azur.launcher.game.LocalVersionScanner;
import com.azur.launcher.game.VersionManager;
import com.azur.launcher.i18n.I18n;
import com.azur.launcher.loader.InstallEntries;
import com.azur.launcher.loader.NeoForgeCatalog;
import com.azur.launcher.loader.NeoForgeList;
import com.azur.launcher.profile.InstanceStore;
import com.azur.launcher.profile.Profile;
import com.azur.launcher.loader.NeoForgeInstaller;
import com.azur.launcher.model.VersionManifest;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** Home page: pick an installed version, install new ones (vanilla or NeoForge), launch and watch the log. */
final class HomePage {

    private final AppContext ctx;
    private final VersionManager versionManager;
    private final VBox root = new VBox(28);

    private final Label heroTitle = UiKit.label("", "home-title");
    private final Label heroSubtitle = UiKit.label("", "home-sub");
    private final Label greeting = UiKit.label("", "home-greeting");
    private final ComboBox<LocalVersion> installedBox = new ComboBox<>();
    private final ObservableList<InstallEntries.Entry> entries = FXCollections.observableArrayList();
    private final FilteredList<InstallEntries.Entry> visibleEntries = new FilteredList<>(entries);
    private final ComboBox<InstallEntries.Entry> entryBox = new ComboBox<>(visibleEntries);
    private final TextField searchField = new TextField();
    private final TextField manualField = new TextField();
    private final Label installNote = UiKit.label("", "banner-warn");
    private List<String> vanillaReleases = List.of();
    private List<String> neoForgeBuilds = List.of();
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
        root.getChildren().addAll(heroCard(), advanced());

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

    private VBox heroCard() {
        heroSubtitle.setWrapText(true);
        VBox hero = new VBox(14, greeting, heroTitle, heroSubtitle, launchButton);
        hero.getStyleClass().add("home-hero");
        hero.setAlignment(Pos.CENTER);
        return hero;
    }

    /** Everything except Play lives in one collapsed section, so the page stays calm. */
    private TitledPane advanced() {
        TitledPane pane = new TitledPane(I18n.tr("home.advanced"),
                new VBox(14, installedCard(), installCard(), logCard()));
        pane.setExpanded(false);
        pane.getStyleClass().add("home-advanced");
        return pane;
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
        searchField.setPromptText(I18n.tr("home.search"));
        searchField.textProperty().addListener((obs, old, text) -> {
            visibleEntries.setPredicate(entry -> InstallEntries.matches(entry, text));
            entryBox.getSelectionModel().selectFirst();
        });
        HBox.setHgrow(searchField, Priority.ALWAYS);
        entryBox.setMaxWidth(Double.MAX_VALUE);
        entryBox.setPromptText(I18n.tr("home.versions_unavailable"));
        entryBox.setCellFactory(list -> new EntryCell());
        entryBox.setButtonCell(new EntryCell());
        HBox.setHgrow(entryBox, Priority.ALWAYS);
        HBox pick = new HBox(8, searchField, entryBox);
        pick.setAlignment(Pos.CENTER_LEFT);
        manualField.setPromptText(I18n.tr("home.neoforge_manual_hint"));
        manualField.setPrefWidth(300);
        Label manualLabel = UiKit.label(I18n.tr("home.neoforge_manual"), "option-title");
        HBox manual = new HBox(8, manualLabel, manualField, installButton);
        manual.setAlignment(Pos.CENTER_LEFT);
        installNote.setWrapText(true);
        installNote.setVisible(false);
        installNote.setManaged(false);
        return UiKit.card(UiKit.cardHeader("icon-java", I18n.tr("home.install_title")),
                UiKit.label(I18n.tr("home.install_hint"), "card-subtitle"), pick, installNote, manual);
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
        AuthAccount account = ctx.accounts.selected();
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
                    vanillaReleases = releases;
                    rebuildEntries();
                });
            } catch (Exception e) {
                ctx.log("Version list unavailable: " + e.getMessage());
                ctx.setStatus(I18n.tr("home.versions_unavailable"));
            }
        });
        loadNeoForge();
    }

    /** NeoForge builds with a timeout; on failure the last saved list is used and the reason is shown in the page. */
    private void loadNeoForge() {
        ctx.io.submit(() -> {
            NeoForgeList.Result result = new NeoForgeList(ctx.dirs.cacheDir().resolve("neoforge-versions.txt"))
                    .load(NeoForgeCatalog::allVersions);
            if (!result.fresh()) {
                ctx.log("NeoForge list unavailable: " + result.error());
            }
            Platform.runLater(() -> {
                neoForgeBuilds = result.versions();
                showNote(result.fresh() ? null : I18n.tr(
                        result.versions().isEmpty() ? "home.neoforge_unavailable" : "home.neoforge_cached",
                        result.error()));
                rebuildEntries();
            });
        });
    }

    private void rebuildEntries() {
        InstallEntries.Entry keep = entryBox.getValue();
        String pinnedMc = null;
        String pinnedNeoForge = null;
        try {
            Profile pin = InstanceStore.bundledProfile();
            pinnedMc = pin.minecraft();
            pinnedNeoForge = pin.neoforge();
        } catch (java.io.IOException e) {
            ctx.log("Pinned profile unreadable: " + e.getMessage());
        }
        entries.setAll(InstallEntries.build(vanillaReleases, neoForgeBuilds, pinnedMc, pinnedNeoForge));
        if (keep != null && entries.contains(keep)) {
            entryBox.setValue(keep);
        } else {
            entryBox.getSelectionModel().selectFirst();
        }
    }

    private void showNote(String text) {
        installNote.setText(text == null ? "" : text);
        installNote.setVisible(text != null);
        installNote.setManaged(text != null);
    }

    // ------------------------------------------------------------------ install

    private void install() {
        if (busy) {
            return;
        }
        String mc;
        String neoForge;
        String manual = manualField.getText().trim();
        if (!manual.isEmpty()) {
            if (!InstallEntries.isValidNeoForge(manual)) {
                showNote(I18n.tr("home.neoforge_manual_bad"));
                return;
            }
            neoForge = manual;
            mc = InstallEntries.mcFor(manual);
        } else {
            InstallEntries.Entry entry = entryBox.getValue();
            if (entry == null) {
                return;
            }
            mc = entry.mc();
            neoForge = entry.neoforge();
        }
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

    /** One row in the install list. */
    private static final class EntryCell extends ListCell<InstallEntries.Entry> {
        @Override
        protected void updateItem(InstallEntries.Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setText(null);
                return;
            }
            String text = entry.isVanilla() ? I18n.tr("home.entry_vanilla", entry.mc())
                    : I18n.tr("home.entry_neoforge", entry.neoforge(), entry.mc());
            setText(entry.pinned() ? I18n.tr("home.entry_pinned", text) : text);
        }
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
