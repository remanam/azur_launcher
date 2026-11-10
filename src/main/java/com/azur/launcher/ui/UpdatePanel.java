package com.azur.launcher.ui;

import com.azur.launcher.i18n.I18n;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.function.Supplier;

/**
 * «Launcher update» block of Settings (next to «About»): the really running version, the state, the time of the last
 * check and the buttons «Check now» / «Restart and update». All decisions are in {@link UpdatePanelState}.
 */
final class UpdatePanel {

    private final Supplier<UpdatePanelState> state;
    private final Runnable check;
    private final Supplier<UpdateNotice.Restart> restart;
    private final Label version = UiKit.label("", "card-subtitle");
    private final Label status = UiKit.label("", "about-text");
    private final Label checked = UiKit.label("", "card-subtitle");
    private final Label hint = UiKit.label("", "about-text");
    private final Button checkButton;
    private final Button restartButton;
    private final VBox box;

    UpdatePanel(Supplier<UpdatePanelState> state, Runnable check,
                Supplier<UpdateNotice.Restart> restart) {
        this.state = state;
        this.check = check;
        this.restart = restart;
        status.setWrapText(true);
        hint.setWrapText(true);
        checkButton = UiKit.button(I18n.tr("updatepanel.check"), "icon-refresh", "button-border", this::onCheck);
        restartButton = UiKit.button(I18n.tr("updatepanel.restart"), null, "button-border", this::onRestart);
        HBox buttons = new HBox(10, checkButton, restartButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox text = new VBox(4, version, status, checked, hint);
        HBox.setHgrow(text, Priority.ALWAYS);
        box = new VBox(10, UiKit.label(I18n.tr("updatepanel.title"), "section-head"), text, buttons);
        refresh();
    }

    VBox node() {
        return box;
    }

    private void onCheck() {
        checkButton.setDisable(true);
        status.setText(I18n.tr("updatepanel.checking"));
        check.run();
    }

    private void onRestart() {
        UpdateNotice.Restart result = restart.get();
        if (result == UpdateNotice.Restart.FAILED) {
            status.setText(I18n.tr("update.manual.text"));
        } else if (result == UpdateNotice.Restart.GAME_BUSY) {
            refresh();
        }
    }

    /** JavaFX thread: after every check and when the page is shown. */
    void refresh() {
        UpdatePanelState s = state.get();
        version.setText(I18n.tr("updatepanel.running", s.running()));
        status.setText(I18n.tr(s.statusKey(), s.statusArg()));
        checked.setText(s.checkedAtMillis() == 0 ? "" : I18n.tr("updatepanel.checked", DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(s.checkedAtMillis()))));
        checked.setManaged(!checked.getText().isEmpty());
        checked.setVisible(!checked.getText().isEmpty());
        hint.setText(s.showTranslocationHint() ? I18n.tr("updatepanel.translocated") : "");
        hint.setManaged(s.showTranslocationHint());
        hint.setVisible(s.showTranslocationHint());
        checkButton.setDisable(!s.canCheck());
        restartButton.setManaged(s.showRestart());
        restartButton.setVisible(s.showRestart());
        restartButton.setDisable(!s.canRestart());
    }
}
