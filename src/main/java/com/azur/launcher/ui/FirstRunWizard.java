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

/** First start, {@link WizardSteps#TOTAL} screens: account, install and play (the bottom button runs the flow). Shown only while no account exists. */
final class FirstRunWizard {

    private final AppContext ctx;
    private final StackPane root = new StackPane();
    private int step;
    private final SelectableText installNote = UiKit.selectable("", "home-sub");

    FirstRunWizard(AppContext ctx) {
        this.ctx = ctx;
        root.setAlignment(Pos.TOP_LEFT);
        showStep(1);
    }

    Node node() {
        return root;
    }

    int step() {
        return step;
    }

    void showStep(int step) {
        this.step = step;
        root.getChildren().setAll(step == 1 ? accountStep() : installStep());
    }

    /** Called by Home: the failure sentence of the install (null = none). The ONE control button is Home's bottom one. */
    void showInstall(String note) {
        installNote.show(note);
    }

    private VBox accountStep() {
        if (ctx.accountController == null) {
            return nickStep();
        }
        SiteLoginPanel panel = ctx.sitePanel();
        if (!ctx.config.isAllowAnonymous()) {
            return step(1, "wizard.1.title_site", "wizard.1.hint_site", panel.node());
        }
        VBox nickBox = nickStep();
        nickBox.setVisible(false);
        nickBox.setManaged(false);
        Label noAccountHint = UiKit.label(I18n.tr("account.nick_only_hint"), "account-note");
        noAccountHint.setVisible(false);
        noAccountHint.setManaged(false);
        Button nickOnly = UiKit.button(I18n.tr("account.btn.nick_only"), null, "button-border", () -> {
            nickBox.setVisible(true);
            nickBox.setManaged(true);
            noAccountHint.setVisible(true);
            noAccountHint.setManaged(true);
        });
        VBox box = step(1, "wizard.1.title_site", "wizard.1.hint_site", panel.node(), nickOnly, noAccountHint, nickBox);
        return box;
    }

    private VBox nickStep() {
        TextField nick = new TextField();
        nick.setPromptText(I18n.tr("wizard.nick_prompt"));
        nick.setMaxWidth(320);
        FieldError nickField = new FieldError(nick);
        nickField.node().setMaxWidth(320);
        Button next = UiKit.button(I18n.tr("wizard.next"), null, "launch-button", () -> {
            if (!nickField.check(FieldRules.nick(nick.getText()))) {
                return;
            }
            String name = nick.getText().trim();
            ctx.accounts.selectOffline(OfflineAccount.of(name));
            try {
                ctx.accounts.save();
            } catch (Exception e) {
                ctx.log("Cannot save accounts: " + e.getMessage());
            }
            ctx.fireAccountsChanged();
            showStep(2);
        });
        return step(1, "wizard.1.title", "wizard.1.hint", nickField.node(), next);
    }

    private VBox installStep() {
        return step(2, "wizard.2.title", "wizard.2.hint", TelemetryOption.notice(ctx), UiKit.withCopy(installNote));
    }

    static VBox step(int n, String titleKey, String hintKey, Node... content) {
        Label hint = UiKit.label(I18n.tr(hintKey), "home-sub");
        hint.setWrapText(true);
        hint.setMaxWidth(420);
        hint.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.HBox head = new javafx.scene.layout.HBox(12, UiKit.label(I18n.tr(titleKey), "card-heading"),
                UiKit.spacer(), UiKit.label(WizardSteps.label(n), "wizard-step"));
        head.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, head, hint);
        box.getChildren().addAll(content);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }
}
