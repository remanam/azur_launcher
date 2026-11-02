package com.azur.launcher.ui;

import com.azur.launcher.api.CommunityApi;
import com.azur.launcher.auth.AuthAccount;
import com.azur.launcher.auth.OfflineAccount;
import com.azur.launcher.i18n.I18n;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Player page: one nickname field, the saved nicknames (select, remove) and the community registration. */
final class AccountsPage {

    private final AppContext ctx;
    private final VBox root = new VBox(14);
    private final VBox accountList = new VBox(8);

    AccountsPage(AppContext ctx) {
        this.ctx = ctx;
        root.getStyleClass().add("page");
        root.getChildren().addAll(
                UiKit.label(I18n.tr("accounts.title"), "page-title"),
                UiKit.label(I18n.tr("accounts.subtitle"), "page-subtitle"),
                nickRow(), accountList, communityBox());
        ctx.onAccountsChanged(this::render);
        render();
    }

    /** Nickname field and «Сохранить»: validates, saves and selects the nickname. */
    private VBox nickRow() {
        TextField nick = new TextField();
        nick.setPromptText(I18n.tr("accounts.nick_prompt"));
        nick.setMaxWidth(320);
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

    /** Community API: registration by code; the API decides roles, and the whitelist flag. */
    private VBox communityBox() {
        TextField apiBase = new TextField(ctx.pack.getApiBase());
        apiBase.setPromptText("https://api.example.com");
        apiBase.textProperty().addListener((obs, old, text) -> {
            ctx.pack.setApiBase(text);
            ctx.savePack();
        });
        TextField code = new TextField();
        code.setPromptText(I18n.tr("community.code_hint"));
        FieldError codeField = new FieldError(code);
        SelectableText message = UiKit.selectable("", "hint");
        Button register = UiKit.button(I18n.tr("community.register"), null, "button-border", () -> { });
        register.setOnAction(event -> register(codeField, message, register));
        return new VBox(6, UiKit.label(I18n.tr("community.title"), "page-subtitle"),
                UiKit.label(I18n.tr("community.hint"), "hint"), apiBase, codeField.node(), register, UiKit.withCopy(message));
    }

    /**
     * Registers this player with a code from the owner: the token goes into the vault, then {@code /v1/me} decides
     * what the launcher shows.
     */
    private void register(FieldError codeField, SelectableText message, Button button) {
        AuthAccount account = ctx.accounts.selected();
        if (account == null) {
            message.setText(I18n.tr("community.sign_in_first"));
            return;
        }
        String code = codeField.text();
        if (!codeField.check(FieldRules.required(code, "field.err.code_required"))) {
            return;
        }
        String base = ctx.pack.getApiBase().trim();
        if (base.isBlank()) {
            message.setText(I18n.tr("community.address_required"));
            return;
        }
        button.setDisable(true);
        message.setText(I18n.tr("community.registering"));
        ctx.io.submit(() -> {
            String text;
            try {
                CommunityApi api = new CommunityApi(base);
                CommunityApi.Session registered = api.register(code, account.getUuid(), account.getUsername());
                ctx.playerTokens.write(registered.token());
                CommunityApi.Session session = api.me(registered.token());
                ctx.applyCommunity(session);
                text = I18n.tr("community.registered",
                        session.nick().isBlank() ? account.getUsername() : session.nick(),
                        session.roles().isEmpty() ? "-" : String.join(", ", session.roles()),
                        I18n.tr(session.whitelisted() ? "community.whitelisted" : "community.not_whitelisted"));
            } catch (Exception e) {
                text = I18n.tr("community.failed",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                ctx.log("Community API: " + text);
            }
            String shown = text;
            Platform.runLater(() -> {
                message.setText(shown);
                button.setDisable(false);
            });
        });
    }

    Node node() {
        return UiKit.pageScroll(root);
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
        return selected != null && selected.getUuid().equalsIgnoreCase(account.getUuid());
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
