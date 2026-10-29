package com.azur.launcher.ui;

import com.azur.launcher.i18n.I18n;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** About: version, GPL-3.0 notice, source offer and third-party licences. */
final class AboutPage {

    private static final String[] FONT_LICENCES = {"/fonts/OFL-Philosopher.txt", "/fonts/OFL-PTSans.txt"};

    private final VBox root = new VBox(14);

    AboutPage() {
        Region logo = new Region();
        logo.getStyleClass().add("about-logo");
        HBox title = new HBox(14, logo, new VBox(3, UiKit.label(I18n.tr("app.title"), "page-title"),
                UiKit.label(I18n.tr("about.version", LauncherApp.VERSION), "page-subtitle")));
        title.setAlignment(Pos.CENTER_LEFT);

        TextArea legalText = new TextArea();
        legalText.setEditable(false);
        legalText.setWrapText(true);
        legalText.setPrefRowCount(12);
        legalText.getStyleClass().add("log-area");
        legalText.setVisible(false);
        legalText.setManaged(false);
        HBox legalButtons = new HBox(8,
                legalButton("about.show_gpl", legalText, "/legal/LICENSE"),
                legalButton("about.show_notice", legalText, "/legal/NOTICE"),
                legalButton("about.show_third", legalText, "/legal/THIRD_PARTY.txt"),
                legalButton("about.show_ofl", legalText, FONT_LICENCES));

        root.getStyleClass().add("page");
        root.getChildren().addAll(title,
                UiKit.card(UiKit.cardHeader("icon-info", I18n.tr("about.licence_title")),
                        text(I18n.tr("about.gpl")), small(I18n.tr("about.upstream"))),
                UiKit.card(UiKit.cardHeader("icon-folder", I18n.tr("about.source_title")),
                        text(I18n.tr("about.source")), small(I18n.tr("about.source_where"))),
                UiKit.card(UiKit.cardHeader("icon-java", I18n.tr("about.third_title")),
                        small("JavaFX 21 - GPL-2.0 with Classpath Exception"),
                        small("Gson - Apache-2.0"),
                        small("Philosopher, PT Sans - SIL Open Font License 1.1"),
                        legalButtons, legalText));
    }

    Node node() {
        return UiKit.pageScroll(root);
    }

    private static Label text(String value) {
        Label label = UiKit.label(value, "about-text");
        label.setWrapText(true);
        return label;
    }

    private static Label small(String value) {
        Label label = UiKit.label(value, "about-small");
        label.setWrapText(true);
        return label;
    }

    private Button legalButton(String key, TextArea target, String... resources) {
        return UiKit.button(I18n.tr(key), null, "button-border", () -> {
            target.setText(readAll(resources));
            target.setVisible(true);
            target.setManaged(true);
            target.positionCaret(0);
        });
    }

    private static String readAll(String... resources) {
        StringBuilder text = new StringBuilder();
        for (String resource : resources) {
            try (InputStream in = AboutPage.class.getResourceAsStream(resource)) {
                if (in != null) {
                    text.append(new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\uFEFF", ""))
                            .append("\n\n----------\n\n");
                }
            } catch (IOException ignored) {
                // the texts are also files next to the launcher; a read failure only leaves the box empty
            }
        }
        return text.toString();
    }
}
