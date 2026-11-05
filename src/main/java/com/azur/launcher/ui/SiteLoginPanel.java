package com.azur.launcher.ui;

import com.azur.launcher.account.AccountController;
import com.azur.launcher.account.AccountFlowState;
import com.azur.launcher.i18n.I18n;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The «sign in with the site» block, drawn from an {@link AccountFlowState} (no logic of its own). Used in the first-run
 * wizard and in «Дополнительно». The code is selectable; the countdown is one label updated once a second while it is shown.
 */
final class SiteLoginPanel {

    private final AccountController controller;
    private final String cabinetUrl;
    private final Consumer<String> open;
    private final LongSupplier clock;
    private final VBox root = new VBox(12);
    private final Timeline ticker = new Timeline();
    private final Label countdown = UiKit.label("", "account-note");
    private AccountFlowState state;

    SiteLoginPanel(AccountController controller, String cabinetUrl, Consumer<String> open, LongSupplier clock) {
        this.controller = controller;
        this.cabinetUrl = cabinetUrl;
        this.open = open;
        this.clock = clock;
        root.setAlignment(Pos.TOP_LEFT);
        ticker.getKeyFrames().add(new KeyFrame(Duration.seconds(1), e -> updateCountdown()));
        ticker.setCycleCount(Animation.INDEFINITE);
    }

    Node node() {
        return root;
    }

    void show(AccountFlowState value) {
        state = value;
        root.getChildren().setAll(switch (value.phase()) {
            case SIGNED_OUT -> signedOut(value);
            case STARTING -> new VBox(8, UiKit.label(I18n.tr("account.starting"), "home-sub"));
            case WAITING -> waiting(value);
            case SIGNED_IN -> signedIn(value);
        });
        if (value.showsCode()) {
            updateCountdown();
            ticker.play();
        } else {
            ticker.stop();
        }
    }

    private Node signedOut(AccountFlowState s) {
        Label hint = UiKit.label(I18n.tr("account.signed_out.hint"), "home-sub");
        wrap(hint);
        VBox box = new VBox(12, hint,
                UiKit.button(I18n.tr("account.btn.sign_in"), null, "launch-button", controller::signIn));
        if (s.errorKey() != null) {
            Label error = UiKit.label(I18n.tr(s.errorKey()), "field-msg");
            error.getStyleClass().add("field-error");
            error.setMinHeight(20);
            box.getChildren().add(error);
        }
        return box;
    }

    private Node waiting(AccountFlowState s) {
        Label code = UiKit.label(s.userCode(), "account-code");
        code.setAccessibleText(I18n.tr("account.code_label") + " " + s.userCode());
        Label hint = UiKit.label(I18n.tr("account.waiting.hint"), "home-sub");
        wrap(hint);
        Label print = UiKit.label(I18n.tr("account.fingerprint", s.fingerprint()), "account-note");
        wrap(print);
        HBox buttons = new HBox(10, UiKit.button(I18n.tr("account.btn.open"), null, "button-border", () -> open.accept(s.verifyUrl())),
                UiKit.button(I18n.tr("account.btn.cancel"), null, "button-border", controller::cancel));
        return new VBox(10, UiKit.label(I18n.tr("account.waiting.title"), "card-heading"), code, countdown, hint, print, buttons);
    }

    private Node signedIn(AccountFlowState s) {
        long days = s.keyAgeDays(clock.getAsLong());
        Label note = UiKit.label(I18n.tr("account.signout_hint"), "account-note");
        wrap(note);
        HBox buttons = new HBox(10, UiKit.button(I18n.tr("account.btn.sign_out"), null, "button-border", controller::signOut),
                UiKit.button(I18n.tr("account.btn.cabinet"), null, "button-border", () -> open.accept(cabinetUrl)));
        return new VBox(8, UiKit.label(I18n.tr("account.signed_in", s.nick()), "card-heading"),
                UiKit.label(I18n.tr("account.device", s.deviceName()), "account-note"),
                UiKit.label(I18n.tr("account.fingerprint_short", s.fingerprint()), "account-note"),
                UiKit.label(I18n.tr("account.key_age", days), "account-note"), note, buttons);
    }

    private static void wrap(Label label) {
        label.setWrapText(true);
        label.setMaxWidth(420);
        label.setAlignment(Pos.TOP_LEFT);
        label.setTextAlignment(javafx.scene.text.TextAlignment.LEFT);
    }

    private void updateCountdown() {
        if (state == null || !state.showsCode()) {
            return;
        }
        long left = state.secondsLeft(clock.getAsLong());
        countdown.setText(I18n.tr("account.left", left / 60, String.format("%02d", left % 60)));
    }
}
