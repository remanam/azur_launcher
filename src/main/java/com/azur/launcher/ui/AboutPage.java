package com.azur.launcher.ui;

import com.azur.launcher.i18n.I18n;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** About: name, version and one sentence about what the launcher does. Licence texts ship as files, not as UI. */
final class AboutPage {

    private static final double LOGO_SIZE = 64;

    private final VBox root = new VBox(10);

    AboutPage() {
        Region logo = new Region();
        logo.getStyleClass().add("about-logo");
        logo.setMinSize(LOGO_SIZE, LOGO_SIZE);
        logo.setPrefSize(LOGO_SIZE, LOGO_SIZE);
        logo.setMaxSize(LOGO_SIZE, LOGO_SIZE);
        Label description = UiKit.label(I18n.tr("about.description"), "about-text");
        description.setWrapText(true);
        root.getStyleClass().add("page");
        root.getChildren().addAll(logo, UiKit.label(I18n.tr("app.title"), "page-title"),
                UiKit.label(I18n.tr("app.version", LauncherApp.VERSION), "page-subtitle"), description);
    }

    Node node() {
        return UiKit.pageScroll(root);
    }
}
