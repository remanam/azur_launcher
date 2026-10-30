package com.azur.launcher.ui;

import com.azur.launcher.auth.AuthSettings;
import com.azur.launcher.game.LocalVersion;
import com.azur.launcher.game.LocalVersionScanner;
import com.azur.launcher.game.VersionManager;
import com.azur.launcher.i18n.I18n;
import com.azur.launcher.net.ProxyConfig;
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
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** Settings > Advanced: Azure client id, proxy, repair, installed versions, Vanilla/NeoForge install and the log. */
final class AdvancedPanel {

    private final AppContext ctx;
    private final VersionManager versionManager;
    
    private final ComboBox<LocalVersion> installedBox = new ComboBox<>();
    private final ObservableList<InstallEntries.Entry> entries = FXCollections.observableArrayList();
    private final FilteredList<InstallEntries.Entry> visibleEntries = new FilteredList<>(entries);
    private final ComboBox<InstallEntries.Entry> entryBox = new ComboBox<>(visibleEntries);
    private final TextField searchField = new TextField();
    private final TextField manualField = new TextField();
    private final SelectableText installNote = UiKit.selectable("", "banner-warn");
    private List<String> vanillaReleases = List.of();
    private List<String> neoForgeBuilds = List.of();
    private final Button installButton;
    private final TextArea logArea = new TextArea();

    private final PlayController play;
    private volatile boolean busy;

    private final TitledPane pane;

    AdvancedPanel(AppContext ctx, PlayController play, Node... topRows) {
        this.ctx = ctx;
        this.play = play;
        this.versionManager = new VersionManager(ctx.gamePaths(), ctx::log);
        installButton = UiKit.button(I18n.tr("home.install"), "icon-plus", "button-raised", this::install);

        VBox content = new VBox(24);
        content.getChildren().add(UiKit.card(topRows));
        content.getChildren().addAll(networkCard(), repairCard(), installedCard(), installCard(), logCard());
        pane = new TitledPane(I18n.tr("settings.advanced"), content);
        pane.setExpanded(false);
        pane.getStyleClass().add("advanced-pane");

        logArea.setText(ctx.logText());
        ctx.bindLog(line -> logArea.appendText(line + "\n"));
        play.onPhase(phase -> installButton.setDisable(phase != PlayController.Phase.IDLE || busy));
        refreshInstalled();
        loadManifest();
    }

    Node node() {
        return pane;
    }

    private VBox repairCard() {
        Button repair = UiKit.button(I18n.tr("settings.repair"), null, "button-border", play::repairCurrent);
        play.onPhase(phase -> repair.setDisable(phase != PlayController.Phase.IDLE));
        Label hint = UiKit.label(I18n.tr("settings.repair_hint"), "card-subtitle");
        hint.setWrapText(true);
        return UiKit.card(UiKit.cardHeader("icon-refresh", I18n.tr("settings.repair")), hint, repair);
    }

    /** Azure client id and the one download proxy used by every request (sign-in, game, NeoForge, pack). */
    private VBox networkCard() {
        AuthSettings settings = ctx.authSettings;
        TextField clientId = new TextField(settings.clientId());
        TextField host = new TextField(settings.proxyHost());
        host.setPromptText(I18n.tr("accounts.proxy_host"));
        TextField port = new TextField(settings.proxyPort() > 0 ? Integer.toString(settings.proxyPort()) : "");
        port.setPromptText(I18n.tr("accounts.proxy_port"));
        TextField user = new TextField();
        user.setPromptText(I18n.tr("settings.proxy_user"));
        PasswordField password = new PasswordField();
        password.setPromptText(I18n.tr("settings.proxy_password"));
        Label proxyHint = UiKit.label(I18n.tr("settings.proxy_hint"), "card-subtitle");
        proxyHint.setWrapText(true);
        SelectableText message = UiKit.selectable("", "hint");
        Button save = UiKit.button(I18n.tr("accounts.save_settings"), null, "button-border", () -> {
            if (!clientId.getText().isBlank() && !AuthSettings.isValidClientId(clientId.getText())) {
                message.setText(I18n.tr("accounts.bad_client_id"));
                return;
            }
            settings.setClientId(clientId.getText());
            settings.setProxy(host.getText(), parsePort(port.getText()));
            ProxyConfig.set(ProxyConfig.of(host.getText(), parsePort(port.getText()), user.getText(), password.getText()));
            try {
                settings.save();
                message.setText(I18n.tr("accounts.settings_saved"));
            } catch (Exception e) {
                message.setText(e.getMessage());
            }
        });
        HBox proxy = new HBox(8, host, port);
        HBox.setHgrow(host, Priority.ALWAYS);
        return UiKit.card(UiKit.cardHeader("icon-account", I18n.tr("accounts.network_title")),
                UiKit.label(I18n.tr("accounts.client_id"), "hint"), clientId,
                UiKit.label(I18n.tr("settings.proxy_title"), "page-subtitle"), proxyHint, proxy, user, password, save, UiKit.withCopy(message));
    }

    private static int parsePort(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 && value < 65536 ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ layout

    private VBox installedCard() {
        installedBox.setMaxWidth(Double.MAX_VALUE);
        installedBox.setPromptText(I18n.tr("home.no_versions"));
        installedBox.setCellFactory(list -> new VersionCell());
        installedBox.setButtonCell(new VersionCell());
        installedBox.valueProperty().addListener((obs, old, value) -> {
            if (value != null) {
                ctx.config.setVersionId(value.id());
            }
        });
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
        return UiKit.card(UiKit.cardHeader("icon-java", I18n.tr("home.install_title")),
                UiKit.label(I18n.tr("home.install_hint"), "card-subtitle"), pick, UiKit.withCopy(installNote), manual);
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
        installNote.show(text);
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
        installButton.setDisable(value || play.phase() != PlayController.Phase.IDLE);
    }

    private void error(String message) {
        UiKit.errorDialog(ctx.stage, message, ctx.config.isDarkMode());
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
