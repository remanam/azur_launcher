package com.azur.launcher.ui;

import com.azur.launcher.i18n.I18n;

import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** The opt-in box for anonymous telemetry (first-run wizard and Settings > Advanced). Off by default; says exactly what is sent. */
final class TelemetryOption {

    private TelemetryOption() {
    }

    static Node node(AppContext ctx) {
        CheckBox box = new CheckBox(I18n.tr("telemetry.title"));
        box.setSelected(ctx.config.isTelemetryEnabled());
        box.setOnAction(e -> apply(ctx, box.isSelected()));
        Label text = UiKit.label(I18n.tr("telemetry.text"), "option-subtitle");
        text.setWrapText(true);
        text.setMaxWidth(420);
        return new VBox(6, box, text);
    }

    /** Switching on creates the install id; switching off forgets it and asks the site to erase what it has. */
    static void apply(AppContext ctx, boolean on) {
        String forgotten = ctx.config.setTelemetryEnabled(on);
        ctx.saveConfig();
        if (!on) {
            ctx.telemetry.forget(forgotten);
        }
    }
}
