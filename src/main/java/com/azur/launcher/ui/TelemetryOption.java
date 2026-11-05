package com.azur.launcher.ui;

import com.azur.launcher.i18n.I18n;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Anonymous telemetry: the one-line notice (wizard step 2, About) and the switch + «Delete my data» (Settings > Advanced). On by default. */
final class TelemetryOption {

    private TelemetryOption() {
    }

    /** The notice instead of a question: what is sent, where to switch it off, link to the privacy page. */
    static Node notice(AppContext ctx) {
        Label text = UiKit.label(I18n.tr("telemetry.notice"), "option-subtitle");
        text.setWrapText(true);
        text.setMaxWidth(420);
        Hyperlink link = new Hyperlink(I18n.tr("telemetry.privacy"));
        link.setOnAction(e -> ctx.host.showDocument(privacyUrl(ctx)));
        return new VBox(2, text, link);
    }

    /** Switch (on by default) and the erase button. */
    static Node node(AppContext ctx) {
        CheckBox box = new CheckBox(I18n.tr("telemetry.title"));
        box.setSelected(ctx.config.isTelemetryEnabled());
        box.setOnAction(e -> apply(ctx, box.isSelected()));
        Button forget = UiKit.button(I18n.tr("telemetry.forget"), "icon-refresh", "button-border", () -> forget(ctx));
        return new VBox(6, box, new HBox(forget));
    }

    /** Switching off forgets the id and asks the site to erase its rows; switching on creates a new id. */
    static void apply(AppContext ctx, boolean on) {
        String forgotten = ctx.config.setTelemetryEnabled(on);
        ctx.saveConfig();
        if (!on) {
            ctx.telemetry.forget(forgotten);
        }
    }

    /** «Delete my data»: erases the site rows of the current id and starts over with a fresh one (or none while off). */
    static void forget(AppContext ctx) {
        boolean on = ctx.config.isTelemetryEnabled();
        String old = ctx.config.setTelemetryEnabled(false);
        if (on) {
            ctx.config.setTelemetryEnabled(true);
        }
        ctx.saveConfig();
        ctx.telemetry.forget(old);
    }

    static String privacyUrl(AppContext ctx) {
        try {
            java.net.URI u = java.net.URI.create(ctx.pack.getServersUrl());
            return "https://" + u.getHost() + "/privacy";
        } catch (RuntimeException e) {
            return "https://example.test/privacy";
        }
    }
}
