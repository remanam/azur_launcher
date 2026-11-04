package com.azur.launcher.ui;

import com.azur.launcher.i18n.I18n;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Supplier;

/**
 * "Launcher updated to X, restart needed": an overlay on Home (scrim + card). The ask view offers "Restart now" and
 * "Later"; a running game postpones it with an explanation; if the restart fails the player is told to close and open
 * the launcher by hand and the only button closes the app. Texts and rules: {@link UpdateNotice}.
 */
final class UpdateDialog {

    private final StackPane scrim = new StackPane();
    private final Label title = UiKit.label("", "update-dialog-title");
    private final Label text = UiKit.label("", "update-dialog-text");
    private final HBox buttons = new HBox(12);
    private final Supplier<UpdateNotice.Restart> restart;
    private final Runnable quit;
    private final Supplier<Boolean> gameBusy;
    private String version = "";

    /**
     * @param restart starts the new copy and closes this one ({@link UpdateController#restart})
     * @param quit closes the launcher without restarting
     */
    UpdateDialog(Supplier<UpdateNotice.Restart> restart, Runnable quit, Supplier<Boolean> gameBusy) {
        this.restart = restart;
        this.quit = quit;
        this.gameBusy = gameBusy;
        text.setWrapText(true);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        VBox card = new VBox(14, title, text, buttons);
        card.getStyleClass().add("update-dialog");
        card.setMaxWidth(460);
        card.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        scrim.getStyleClass().add("update-scrim");
        scrim.setAlignment(Pos.CENTER);
        scrim.getChildren().add(card);
        scrim.setVisible(false);
        scrim.setManaged(false);
    }

    StackPane node() {
        return scrim;
    }

    void open(String newVersion) {
        version = newVersion;
        show(UpdateNotice.dialog(gameBusy.get()));
    }

    void close() {
        scrim.setVisible(false);
        scrim.setManaged(false);
    }

    private void show(UpdateNotice.Dialog view) {
        buttons.getChildren().clear();
        switch (view) {
            case ASK -> {
                title.setText(I18n.tr("update.dialog.title", version));
                text.setText(I18n.tr("update.dialog.text"));
                buttons.getChildren().addAll(UiKit.button(I18n.tr("update.later"), null, "update-later", this::close),
                        UiKit.button(I18n.tr("update.restart_now"), null, "update-restart", this::restartNow));
            }
            case GAME_RUNNING -> {
                title.setText(I18n.tr("update.dialog.title", version));
                text.setText(I18n.tr("update.dialog.game"));
                buttons.getChildren().add(UiKit.button(I18n.tr("update.ok"), null, "update-restart", this::close));
            }
            case CLOSE_MANUALLY -> {
                title.setText(I18n.tr("update.manual.title"));
                text.setText(I18n.tr("update.manual.text"));
                buttons.getChildren().add(UiKit.button(I18n.tr("update.close_launcher"), null, "update-restart", quit));
            }
        }
        scrim.setVisible(true);
        scrim.setManaged(true);
    }

    private void restartNow() {
        UpdateNotice.Restart result = restart.get();
        if (result != UpdateNotice.Restart.STARTED) {
            show(UpdateNotice.dialogAfter(result));
        }
    }
}
