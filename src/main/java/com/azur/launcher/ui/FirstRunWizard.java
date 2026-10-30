package com.azur.launcher.ui;

import com.azur.launcher.auth.OfflineAccount;
import com.azur.launcher.i18n.I18n;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** First start, three screens: nickname or Microsoft, pack install, Play. Shown only while no account exists. */
final class FirstRunWizard {

    private final AppContext ctx;
    private final Runnable openAccounts;
    private final Runnable install;
    private final Runnable play;
    private final StackPane root = new StackPane();
    private final Label nickError = UiKit.label("", "home-error");
    private final InstallProgressView progress = new InstallProgressView();
    private int step;
    private final Button installButton;
    private final SelectableText installNote = UiKit.selectable("", "home-sub");

    FirstRunWizard(AppContext ctx, Runnable openAccounts, Runnable install, Runnable play) {
        this.ctx = ctx;
        this.openAccounts = openAccounts;
        this.install = install;
        this.play = play;
        installButton = UiKit.button(I18n.tr("wizard.install"), null, "launch-button", install);
        root.setAlignment(Pos.TOP_CENTER);
        showStep(1);
    }

    Node node() {
        return root;
    }

    /** The install progress block of step 2 (Home feeds it). */
    InstallProgressView progress() {
        return progress;
    }

    int step() {
        return step;
    }

    void showStep(int step) {
        this.step = step;
        root.getChildren().setAll(switch (step) {
            case 1 -> accountStep();
            case 2 -> installStep();
            default -> playStep();
        });
    }

    /** Called by Home while the install runs: the label with percent, or the failure sentence. */
    void showInstall(String buttonText, boolean busy, String note) {
        installButton.setText(buttonText);
        installButton.setDisable(busy);
        installNote.show(note);
    }

    private VBox accountStep() {
        TextField nick = new TextField();
        nick.setPromptText(I18n.tr("wizard.nick_prompt"));
        nick.setMaxWidth(320);
        nickError.setVisible(false);
        nickError.setManaged(false);
        Button next = UiKit.button(I18n.tr("wizard.next"), null, "launch-button", () -> {
            String name = nick.getText().trim();
            if (!OfflineAccount.isValidName(name)) {
                nickError.setText(I18n.tr("accounts.offline_bad_nick"));
                nickError.setVisible(true);
                nickError.setManaged(true);
                return;
            }
            ctx.accounts.selectOffline(OfflineAccount.of(name));
            try {
                ctx.accounts.save();
            } catch (Exception e) {
                ctx.log("Cannot save accounts: " + e.getMessage());
            }
            ctx.fireAccountsChanged();
            showStep(2);
        });
        Button microsoft = UiKit.button(I18n.tr("wizard.ms"), null, "button-border", openAccounts);
        return step(1, "wizard.1.title", "wizard.1.hint", nick, nickError, next, microsoft);
    }

    private VBox installStep() {
        return step(2, "wizard.2.title", "wizard.2.hint", installButton, progress.node(), UiKit.withCopy(installNote));
    }

    private VBox playStep() {
        return step(3, "wizard.3.title", "wizard.3.hint",
                UiKit.button(I18n.tr("home.btn.play"), "icon-play", "launch-button", play));
    }

    private static VBox step(int n, String titleKey, String hintKey, Node... content) {
        Label hint = UiKit.label(I18n.tr(hintKey), "home-sub");
        hint.setWrapText(true);
        hint.setMaxWidth(420);
        VBox box = new VBox(24, UiKit.label(I18n.tr("wizard.step", n), "wizard-step"),
                UiKit.label(I18n.tr(titleKey), "home-title"), hint);
        box.getChildren().addAll(content);
        box.setAlignment(Pos.TOP_CENTER);
        box.getStyleClass().add("home-hero");
        return box;
    }
}
