package com.azur.launcher.ui;

import com.azur.launcher.api.CommunityApi;
import com.azur.launcher.auth.AuthAccount;
import com.azur.launcher.auth.AuthException;
import com.azur.launcher.auth.AuthNetwork;
import com.azur.launcher.auth.OfflineAccount;
import com.azur.launcher.auth.MicrosoftAccount;
import com.azur.launcher.auth.MicrosoftAuthService;
import com.azur.launcher.i18n.I18n;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Microsoft and offline accounts: list, select, remove and the device-code sign-in dialog. */
final class AccountsPage {

    private final AppContext ctx;
    private final VBox root = new VBox(14);
    private final VBox accountList = new VBox(8);
    private Button signInButton;
    private boolean signingIn;

    AccountsPage(AppContext ctx) {
        this.ctx = ctx;
        signInButton = UiKit.button(I18n.tr("accounts.sign_in"), "icon-account", "button-raised", this::signIn);
        Label hint = UiKit.label(I18n.tr("accounts.licence_hint"), "hint");
        hint.setWrapText(true);
        root.getStyleClass().add("page");
        root.getChildren().addAll(
                UiKit.label(I18n.tr("accounts.title"), "page-title"),
                UiKit.label(I18n.tr("accounts.subtitle"), "page-subtitle"),
                hint, signInRow(), accountList, communityBox());
        ctx.onAccountsChanged(this::render);
        render();
    }

    /** Microsoft sign-in button with the offline-account button next to it. */
    private HBox signInRow() {
        return new HBox(10, signInButton, offlineButton());
    }

    /** Community API: registration by code; the API decides roles, the whitelist flag and the pack invite. */
    private VBox communityBox() {
        TextField apiBase = new TextField(ctx.pack.getApiBase());
        apiBase.setPromptText("https://api.example.com");
        apiBase.textProperty().addListener((obs, old, text) -> {
            ctx.pack.setApiBase(text);
            ctx.savePack();
        });
        TextField code = new TextField();
        code.setPromptText(I18n.tr("community.code_hint"));
        Label message = UiKit.label("", "hint");
        message.setWrapText(true);
        Button register = UiKit.button(I18n.tr("community.register"), null, "button-border", () -> { });
        register.setOnAction(event -> register(code.getText(), message, register));
        return new VBox(6, UiKit.label(I18n.tr("community.title"), "page-subtitle"),
                UiKit.label(I18n.tr("community.hint"), "hint"), apiBase, code, register, message);
    }

    /**
     * Registers this player with a code from the owner: the token goes into the vault, then {@code /v1/me} decides
     * what the launcher shows and which pack invite it uses. The Microsoft sign-in is untouched by this.
     */
    private void register(String code, Label message, Button button) {
        AuthAccount account = ctx.accounts.selected();
        if (account == null) {
            message.setText(I18n.tr("community.sign_in_first"));
            return;
        }
        if (code == null || code.isBlank()) {
            message.setText(I18n.tr("community.code_required"));
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

    /** Nickname-only account for friends' offline-mode servers: asks for a valid nick, then selects it. */
    private Button offlineButton() {
        return UiKit.button(I18n.tr("accounts.offline"), null, "button-border", () -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.initOwner(ctx.stage);
            dialog.setHeaderText(I18n.tr("accounts.offline"));
            dialog.setContentText(I18n.tr("accounts.offline_nick"));
            dialog.showAndWait().ifPresent(name -> {
                if (!OfflineAccount.isValidName(name.trim())) {
                    new Alert(Alert.AlertType.WARNING, I18n.tr("accounts.offline_bad_nick")).showAndWait();
                    return;
                }
                ctx.accounts.selectOffline(OfflineAccount.of(name));
                persistAndNotify();
            });
        });
    }

    Node node() {
        return UiKit.pageScroll(root);
    }

    private void render() {
        accountList.getChildren().clear();
        var microsoft = ctx.accounts.microsoftAccounts();
        var offline = ctx.accounts.offlineAccounts();
        if (microsoft.isEmpty() && offline.isEmpty()) {
            accountList.getChildren().add(UiKit.label(I18n.tr("accounts.empty"), "empty-hint"));
            return;
        }
        AuthAccount selected = ctx.accounts.selected();
        for (MicrosoftAccount account : microsoft) {
            accountList.getChildren().add(row(account, "accounts.msa", isActive(selected, account)));
        }
        for (OfflineAccount account : offline) {
            accountList.getChildren().add(row(account, "accounts.offline_tag", isActive(selected, account)));
        }
    }

    private static boolean isActive(AuthAccount selected, AuthAccount account) {
        return selected != null && selected.getUuid().equalsIgnoreCase(account.getUuid());
    }

    private HBox row(AuthAccount account, String kindKey, boolean active) {
        Label name = UiKit.label(account.getUsername(), "two-line-title");
        Label tag = UiKit.label(I18n.tr(active ? "accounts.active" : kindKey), "tag");
        if (active) {
            tag.getStyleClass().add("tag-installed");
        }
        HBox firstLine = new HBox(8, name, tag);
        firstLine.setAlignment(Pos.CENTER_LEFT);
        VBox text = new VBox(3, firstLine, UiKit.label(account.getUuid(), "two-line-subtitle"));
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

    // ------------------------------------------------------------------ sign-in

    private void signIn() {
        if (signingIn) {
            return;
        }
        signingIn = true;
        signInButton.setDisable(true);

        MicrosoftAuthService service = new MicrosoftAuthService(AuthNetwork.standard(ctx.authSettings));
        Label code = new Label("---- ----");
        code.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");
        Label uri = new Label(I18n.tr("signin.requesting"));
        uri.setWrapText(true);
        Label status = UiKit.label("", "option-subtitle");
        status.setWrapText(true);
        Label steps = UiKit.label(I18n.tr("signin.steps"), "option-subtitle");
        steps.setWrapText(true);
        VBox content = new VBox(8, steps, code, uri, status);
        content.setPrefWidth(440);

        ButtonType openType = new ButtonType(I18n.tr("signin.open_browser"), ButtonBar.ButtonData.OTHER);
        ButtonType copyType = new ButtonType(I18n.tr("signin.copy_code"), ButtonBar.ButtonData.OTHER);
        ButtonType cancelType = new ButtonType(I18n.tr("common.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.NONE);
        alert.initOwner(ctx.stage);
        alert.setTitle(I18n.tr("signin.title"));
        alert.setHeaderText(I18n.tr("signin.header"));
        DialogPane pane = alert.getDialogPane();
        pane.setContent(content);
        pane.getButtonTypes().setAll(openType, copyType, cancelType);
        UiKit.styleDialog(pane, ctx.config.isDarkMode());
        Button openButton = (Button) pane.lookupButton(openType);
        Button copyButton = (Button) pane.lookupButton(copyType);
        openButton.setDisable(true);
        copyButton.setDisable(true);

        String[] verificationUri = {""};
        boolean[] finished = {false};
        openButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            ctx.host.showDocument(verificationUri[0]);
        });
        copyButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            ClipboardContent clip = new ClipboardContent();
            clip.putString(code.getText());
            Clipboard.getSystemClipboard().setContent(clip);
            status.setText(I18n.tr("signin.code_copied"));
        });
        alert.setOnHidden(event -> {
            if (!finished[0]) {
                service.cancel();
            }
            signingIn = false;
            signInButton.setDisable(false);
        });
        alert.show();

        String clientId = ctx.authSettings.clientId();
        ctx.io.submit(() -> {
            try {
                MicrosoftAuthService.DeviceCode device = service.requestDeviceCode(clientId);
                Platform.runLater(() -> {
                    code.setText(device.getUserCode());
                    uri.setText(device.getVerificationUri());
                    verificationUri[0] = device.getVerificationUri();
                    openButton.setDisable(false);
                    copyButton.setDisable(false);
                });
                MicrosoftAccount account = service.login(device, clientId,
                        message -> Platform.runLater(() -> status.setText(message)));
                Platform.runLater(() -> {
                    finished[0] = true;
                    ctx.accounts.select(account);
                    persistAndNotify();
                    ctx.log("Signed in: " + account.getUsername());
                    alert.close();
                });
            } catch (AuthException e) {
                Platform.runLater(() -> {
                    if (!"cancelled".equals(e.getCode())) {
                        status.setText(e.getMessage());
                        openButton.setDisable(true);
                        copyButton.setDisable(true);
                    }
                });
            }
        });
    }
}
