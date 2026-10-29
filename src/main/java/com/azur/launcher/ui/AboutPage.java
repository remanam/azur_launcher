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

        TextArea fontText = new TextArea(fontLicenceText());
        fontText.setEditable(false);
        fontText.setWrapText(true);
        fontText.setPrefRowCount(10);
        fontText.getStyleClass().add("log-area");
        fontText.setVisible(false);
        fontText.setManaged(false);
        Button toggle = UiKit.button(I18n.tr("about.show_ofl"), null, "button-border", () -> {
            boolean show = !fontText.isVisible();
            fontText.setVisible(show);
            fontText.setManaged(show);
        });

        root.getStyleClass().add("page");
        root.getChildren().addAll(title,
                UiKit.card(UiKit.cardHeader("icon-info", I18n.tr("about.licence_title")),
                        text(I18n.tr("about.gpl")), small(I18n.tr("about.upstream"))),
                UiKit.card(UiKit.cardHeader("icon-folder", I18n.tr("about.source_title")),
                        text(I18n.tr("about.source"))),
                UiKit.card(UiKit.cardHeader("icon-java", I18n.tr("about.third_title")),
                        small("JavaFX 21 - GPL-2.0 with Classpath Exception"),
                        small("Gson - Apache-2.0"),
                        small("Philosopher, PT Sans - SIL Open Font License 1.1"),
                        toggle, fontText));
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

    private static String fontLicenceText() {
        StringBuilder text = new StringBuilder();
        for (String resource : FONT_LICENCES) {
            try (InputStream in = AboutPage.class.getResourceAsStream(resource)) {
                if (in != null) {
                    text.append(new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("﻿", ""))
                            .append("\n\n----------\n\n");
                }
            } catch (IOException ignored) {
                // the licence text is a convenience copy; the files ship in the jar next to the fonts
            }
        }
        return text.toString();
    }
}
