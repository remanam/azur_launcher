package com.azur.launcher.ui;

import com.azur.launcher.game.JavaDetector;
import com.azur.launcher.i18n.I18n;
import com.azur.launcher.net.HttpUtils;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.List;

/** Settings: memory, Java, download threads, JVM arguments, language, server list and pack sync. Every change is saved at once. */
final class SettingsPage {

    private static final int MEMORY_MIN_MB = 512;
    private static final int MEMORY_MAX_MB = 32768;
    private static final int MEMORY_STEP_MB = 512;
    private static final List<String> LANGUAGES = List.of("ru", "en");

    private final AppContext ctx;
    private final Runnable onLanguageChanged;
    private final VBox root = new VBox(0);
    private final ComboBox<String> javaBox = new ComboBox<>();
    private List<JavaDetector.JavaInfo> detected = List.of();

    SettingsPage(AppContext ctx, Runnable onLanguageChanged, PlayController play) {
        this.ctx = ctx;
        this.onLanguageChanged = onLanguageChanged;

        VBox header = new VBox(3, UiKit.label(I18n.tr("settings.title"), "page-title"),
                UiKit.label(I18n.tr("settings.subtitle"), "page-subtitle"));
        VBox options = new VBox(0, languageRow(), serversUrlRow(), javaRow(), threadsRow(), jvmArgsRow(),
                folderRow());
        AdvancedPanel advanced = new AdvancedPanel(ctx, play, memoryRow());
        VBox page = new VBox(24, header, UiKit.card(options), advanced.node());
        page.getStyleClass().add("page");
        root.getChildren().add(page);
        detectJava();
    }

    Node node() {
        return UiKit.pageScroll(root);
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

    private Node threadsRow() {
        Slider slider = new Slider(1, 64, ctx.config.getDownloadThreads());
        slider.setPrefWidth(260);
        Label value = UiKit.label(String.valueOf(ctx.config.getDownloadThreads()), "value-label");
        slider.valueProperty().addListener((obs, old, v) -> {
            ctx.config.setDownloadThreads(v.intValue());
            value.setText(String.valueOf(ctx.config.getDownloadThreads()));
            ctx.saveConfig();
        });
        return UiKit.optionItem(I18n.tr("settings.threads"), I18n.tr("settings.threads_hint"), slider, value);
    }

    private Node jvmArgsRow() {
        TextField field = new TextField(ctx.config.getJvmArgs());
        field.setPrefWidth(320);
        field.textProperty().addListener((obs, old, text) -> {
            ctx.config.setJvmArgs(text);
            ctx.saveConfig();
        });
        return UiKit.optionItem(I18n.tr("settings.jvm_args"), I18n.tr("settings.jvm_args_hint"), field);
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

    private Node serversUrlRow() {
        // A new address must also become an allowed host, otherwise the download layer blocks it.
        return textRow("settings.servers_url", "settings.servers_url_hint", ctx.pack.getServersUrl(), value -> {
            ctx.pack.setServersUrl(value);
            HttpUtils.allowHosts(value);
        });
    }

    private Node textRow(String titleKey, String hintKey, String value, java.util.function.Consumer<String> setter) {
        TextField field = new TextField(value);
        field.setPrefWidth(320);
        field.textProperty().addListener((obs, old, text) -> {
            setter.accept(text);
            ctx.savePack();
        });
        return UiKit.optionItem(I18n.tr(titleKey), I18n.tr(hintKey), field);
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
