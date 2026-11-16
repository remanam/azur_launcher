package com.azur.launcher.ui;

import java.util.Objects;
import com.azur.launcher.auth.AuthAccount;
import com.azur.launcher.auth.OfflineAccount;
import com.azur.launcher.i18n.I18n;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** «Игрок» block of the Advanced page: one nickname field, the saved nicknames (select, remove). */
final class PlayerSection {

    private final AppContext ctx;
    private final VBox root = new VBox(14);
    private final VBox accountList = new VBox(8);

    PlayerSection(AppContext ctx) {
        this.ctx = ctx;
        root.getChildren().addAll(nickRow(), accountList);
        ctx.onAccountsChanged(this::render);
        render();
    }

    /** Nickname field and «Сохранить»: validates, saves and selects the nickname. */
    private VBox nickRow() {
        TextField nick = new TextField();
        nick.setPromptText(I18n.tr("accounts.nick_prompt"));
        nick.setPrefWidth(320);
        FieldError nickField = new FieldError(nick);
        Runnable save = () -> {
            if (nickField.check(FieldRules.nick(nick.getText()))) {
                ctx.accounts.selectOffline(OfflineAccount.of(nick.getText().trim()));
                nick.clear();
                persistAndNotify();
            }
        };
        nick.setOnAction(e -> save.run());
        Button saveButton = UiKit.button(I18n.tr("accounts.save"), null, "button-raised", save);
        HBox row = new HBox(10, nickField.node(), saveButton);
        row.setAlignment(Pos.TOP_LEFT);
        return new VBox(6, row);
    }

    Node node() {
        return root;
    }

    private void render() {
        accountList.getChildren().clear();
        var accounts = ctx.accounts.offlineAccounts();
        AuthAccount selected = ctx.accounts.selected();
        for (OfflineAccount account : accounts) {
            accountList.getChildren().add(row(account, isActive(selected, account)));
        }
    }

    private static boolean isActive(AuthAccount selected, AuthAccount account) {
        return Objects.nonNull(selected) && selected.getUuid().equalsIgnoreCase(account.getUuid());
    }

    private HBox row(AuthAccount account, boolean active) {
        Label name = UiKit.label(account.getUsername(), "two-line-title");
        HBox firstLine = new HBox(8, name);
        if (active) {
            Label tag = UiKit.label(I18n.tr("accounts.active"), "tag");
            tag.getStyleClass().add("tag-installed");
            firstLine.getChildren().add(tag);
        }
        firstLine.setAlignment(Pos.CENTER_LEFT);
        VBox text = new VBox(3, firstLine);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox row = new HBox(12, UiKit.avatar(account.getUsername(), "list-avatar", "list-avatar-text"), text);
        row.getStyleClass().add("account-card");
        if (active) {
            row.getStyleClass().add("selected");
        }
        HBox actions = new HBox(8);
        if (!active) {
            actions.getChildren().add(UiKit.button(I18n.tr("accounts.use"), null, "button-border", () -> {
                ctx.accounts.selectExisting(account);
                persistAndNotify();
            }));
        }
        actions.getChildren().add(UiKit.button(I18n.tr("accounts.remove"), null, "button-border", () -> {
            ctx.accounts.remove(account.getUuid());
            persistAndNotify();
        }));
        actions.setAlignment(Pos.CENTER_RIGHT);
        row.getChildren().add(actions);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void persistAndNotify() {
        try {
            ctx.accounts.save();
        } catch (Exception e) {
            ctx.log("Cannot save accounts: " + e.getMessage());
        }
        ctx.fireAccountsChanged();
    }
}
