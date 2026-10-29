package com.azur.launcher.ui;

import com.azur.launcher.auth.AuthException;
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
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Microsoft accounts: list, select, remove and the device-code sign-in dialog. */
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
                hint, signInButton, accountList);
        ctx.onAccountsChanged(this::render);
        render();
    }

    Node node() {
        return UiKit.pageScroll(root);
    }

    private void render() {
        accountList.getChildren().clear();
        var accounts = ctx.accounts.microsoftAccounts();
        if (accounts.isEmpty()) {
            accountList.getChildren().add(UiKit.label(I18n.tr("accounts.empty"), "empty-hint"));
            return;
        }
        MicrosoftAccount selected = ctx.accounts.selectedAccount();
        for (MicrosoftAccount account : accounts) {
            boolean active = selected != null && selected.getUuid().equalsIgnoreCase(account.getUuid());
            accountList.getChildren().add(row(account, active));
        }
    }

    private HBox row(MicrosoftAccount account, boolean active) {
        Label name = UiKit.label(account.getUsername(), "two-line-title");
        Label tag = UiKit.label(I18n.tr(active ? "accounts.active" : "accounts.msa"), "tag");
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
                ctx.accounts.select(account);
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

        MicrosoftAuthService service = new MicrosoftAuthService();
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

        String clientId = ctx.config.getClientId();
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
