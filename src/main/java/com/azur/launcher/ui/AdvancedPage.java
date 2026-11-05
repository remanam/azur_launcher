package com.azur.launcher.ui;

import com.azur.launcher.game.JavaDetector;
import com.azur.launcher.i18n.I18n;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * «Дополнительно» (the gear): player nickname, language, memory, Java, data folder, log, repair and, at the very bottom,
 * «О программе». Every change is saved at once. Everything the player does not need (proxy, versions, thread count,
 * JVM arguments, server list address) is not in the interface.
 */
final class AdvancedPage {

    private static final int MEMORY_MIN_MB = 512;
    private static final int MEMORY_MAX_MB = 32768;
    private static final int MEMORY_STEP_MB = 512;
    private static final List<String> LANGUAGES = List.of("ru", "en");

    private final AppContext ctx;
    private final Runnable onLanguageChanged;
    private final VBox root = new VBox(0);
    private final ComboBox<String> javaBox = new ComboBox<>();
    private List<JavaDetector.JavaInfo> detected = List.of();

    AdvancedPage(AppContext ctx, Runnable onLanguageChanged, PlayController play, Runnable onBack) {
        this.ctx = ctx;
        this.onLanguageChanged = onLanguageChanged;

        Button back = UiKit.button(I18n.tr("advanced.back"), null, "button-border", onBack);
        HBox header = new HBox(16, back, UiKit.label(I18n.tr("settings.advanced"), "page-title"));
        header.setAlignment(Pos.CENTER_LEFT);
        VBox options = new VBox(0, languageRow(), memoryRow(), javaRow(), folderRow(), logRow(), telemetryRow());
        VBox player = UiKit.card(UiKit.label(I18n.tr("advanced.player"), "card-title"), new PlayerSection(ctx).node());
        VBox page = new VBox(18, header);
        if (ctx.accountController != null) {
            VBox account = UiKit.card(UiKit.label(I18n.tr("account.title"), "card-title"), ctx.sitePanel().node());
            page.getChildren().add(account);
            ctx.bindAccount(state -> {
                player.setVisible(!state.signedIn());
                player.setManaged(!state.signedIn());
            });
        }
        page.getChildren().addAll(player, UiKit.card(options), repairCard(play), aboutCard());
        page.getStyleClass().add("page");
        root.getChildren().add(page);
        detectJava();
    }

    Node node() {
        return UiKit.pageScroll(root);
    }

    private VBox repairCard(PlayController play) {
        Button repair = UiKit.button(I18n.tr("settings.repair"), "icon-refresh", "button-border", play::repairCurrent);
        play.onPhase(phase -> repair.setDisable(phase != PlayController.Phase.IDLE));
        return UiKit.card(UiKit.optionItem(I18n.tr("settings.repair"), I18n.tr("settings.repair_hint"), repair));
    }

    private Node telemetryRow() {
        HBox row = new HBox(TelemetryOption.node(ctx));
        row.getStyleClass().add("options-list-item");
        return row;
    }

    private Node logRow() {
        Button open = UiKit.button(I18n.tr("advanced.logs_open"), "icon-folder", "button-border",
                () -> ctx.openInSystem(ctx.dirs.logsDir()));
        return UiKit.optionItem(I18n.tr("advanced.logs"), I18n.tr("advanced.logs_hint"), open);
    }

    /** «О программе»: the last block of the page, logo, name, version, one sentence. */
    private VBox aboutCard() {
        Region logo = new Region();
        logo.getStyleClass().add("about-logo");
        logo.setMinSize(48, 48);
        logo.setPrefSize(48, 48);
        logo.setMaxSize(48, 48);
        Label description = UiKit.label(I18n.tr("about.description"), "about-text");
        description.setWrapText(true);
        VBox text = new VBox(2, UiKit.label(I18n.tr("app.title"), "card-title"),
                UiKit.label(I18n.tr("app.version", LauncherApp.VERSION), "card-subtitle"), description);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(16, logo, text);
        row.setAlignment(Pos.CENTER_LEFT);
        return UiKit.card(UiKit.label(I18n.tr("advanced.about"), "section-head"), row, TelemetryOption.notice(ctx));
    }

    private Node memoryRow() {
        Slider slider = new Slider(MEMORY_MIN_MB, MEMORY_MAX_MB, alignMemory(ctx.config.getMaxMemoryMb()));
        slider.setBlockIncrement(MEMORY_STEP_MB);
        slider.setPrefWidth(260);
        Label value = UiKit.label(memoryText((int) slider.getValue()), "value-label");
        slider.valueProperty().addListener((obs, old, v) -> {
            int mb = alignMemory(v.intValue());
            value.setText(memoryText(mb));
            ctx.config.setMaxMemoryMb(mb);
            ctx.saveConfig();
        });
        return UiKit.optionItem(I18n.tr("settings.memory"), I18n.tr("settings.memory_hint"), slider, value);
    }

    private Node javaRow() {
        javaBox.setPrefWidth(320);
        javaBox.getItems().setAll(I18n.tr("settings.java_auto"));
        javaBox.getSelectionModel().selectFirst();
        javaBox.setOnAction(event -> {
            int index = javaBox.getSelectionModel().getSelectedIndex();
            if (index < 0 || detected.isEmpty() && index > 0) {
                return;
            }
            ctx.config.setJavaPath(index == 0 ? "" : detected.get(index - 1).getJavaExecutable().toString());
            ctx.saveConfig();
        });
        return UiKit.optionItem(I18n.tr("settings.java"), I18n.tr("settings.java_hint"), javaBox);
    }

    private Node languageRow() {
        ComboBox<String> box = new ComboBox<>();
        box.getItems().setAll(LANGUAGES);
        box.setValue(I18n.resolve(ctx.config.getLanguage()));
        box.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String code) {
                return I18n.tr("settings.lang." + code);
            }

            @Override
            public String fromString(String text) {
                return text;
            }
        });
        box.setOnAction(event -> {
            if (!box.getValue().equals(I18n.resolve(ctx.config.getLanguage()))) {
                ctx.config.setLanguage(box.getValue());
                ctx.saveConfig();
                I18n.setLanguage(box.getValue());
                Platform.runLater(onLanguageChanged);
            }
        });
        return UiKit.optionItem(I18n.tr("settings.language"), I18n.tr("settings.language_hint"), box);
    }

    private Node folderRow() {
        Button open = UiKit.button(I18n.tr("home.open_folder"), "icon-folder", "button-border",
                () -> ctx.openInSystem(ctx.gameDir()));
        Label path = UiKit.label(ctx.gameDir().toString(), "path-value");
        path.setWrapText(true);
        return UiKit.optionItem(I18n.tr("settings.data"), ctx.dirs.root().toString(), path, open);
    }

    /** Scans for Java runtimes off the UI thread and fills the combo, keeping the saved choice. */
    private void detectJava() {
        ctx.io.submit(() -> {
            List<JavaDetector.JavaInfo> found = JavaDetector.detectAll();
            Platform.runLater(() -> {
                detected = found;
                javaBox.getItems().setAll(I18n.tr("settings.java_auto"));
                found.forEach(info -> javaBox.getItems().add(info.toString()));
                String saved = ctx.config.getJavaPath();
                int selected = 0;
                for (int i = 0; i < found.size(); i++) {
                    if (found.get(i).getJavaExecutable().toString().equals(saved)) {
                        selected = i + 1;
                    }
                }
                javaBox.getSelectionModel().select(selected);
            });
        });
    }

    static int alignMemory(int value) {
        int aligned = Math.round(value / (float) MEMORY_STEP_MB) * MEMORY_STEP_MB;
        return Math.min(MEMORY_MAX_MB, Math.max(MEMORY_MIN_MB, aligned));
    }

    private static String memoryText(int mb) {
        return mb % 1024 == 0 ? (mb / 1024) + " GB" : mb + " MB";
    }
}
