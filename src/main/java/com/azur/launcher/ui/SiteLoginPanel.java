package com.azur.launcher.ui;

import com.azur.launcher.account.AccountController;
import com.azur.launcher.account.AccountFlowState;
import com.azur.launcher.account.SignInUi;
import com.azur.launcher.i18n.I18n;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
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
    private final String registerUrl;
    private final String resetUrl;
    private final boolean codeLogin;
    private final Consumer<String> open;
    private final LongSupplier clock;
    private final VBox root = new VBox(12);
    private final Timeline ticker = new Timeline();
    private final Label countdown = UiKit.label("", "account-note");
    private AccountFlowState state;
    private SignInForm form;
    boolean submitted; // package-private for FormProbe

    SiteLoginPanel(AccountController controller, String cabinetUrl, String registerUrl, String resetUrl, boolean codeLogin, Consumer<String> open,
                   LongSupplier clock) {
        this.controller = controller;
        this.cabinetUrl = cabinetUrl;
        this.registerUrl = registerUrl;
        this.resetUrl = resetUrl;
        this.codeLogin = codeLogin;
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
        if (value.phase() != AccountFlowState.Phase.STARTING) {
            submitted = false;
        }
        root.getChildren().setAll(switch (value.phase()) {
            case SIGNED_OUT -> signedOut(value);
            case STARTING -> submitted ? signedOut(value) : new VBox(8, UiKit.label(I18n.tr("account.starting"), "home-sub"));
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

    /**
     * Default sign-in: «e-mail or nick» + password (over https to the site only). The form is built once and reused, so a
     * running request keeps the same screen: the button shows a spinner, fields lock, a failure is a plate under the fields.
     */
    private Node signedOut(AccountFlowState s) {
        if (form == null) {
            form = buildForm();
        }
        form.apply(SignInUi.button(s, submitted), SignInUi.notice(s.errorKey()));
        return form.root;
    }

    private SignInForm buildForm() {
        SignInForm f = new SignInForm();
        Runnable submit = () -> {
            if (f.locked) {
                return;
            }
            if (f.login.getText().isBlank() || f.password.getText().isEmpty()) {
                f.apply(SignInUi.Button.ERROR, SignInUi.notice("account.err.fill_both"));
                return;
            }
            char[] secret = f.password.getText().toCharArray();
            f.password.clear();
            submitted = true;
            f.apply(SignInUi.Button.LOADING, null);
            controller.signInWithPassword(f.login.getText(), secret, I18n.language());
        };
        f.login.setOnAction(e -> f.password.requestFocus());
        f.password.setOnAction(e -> submit.run());
        f.signIn.setOnAction(e -> submit.run());
        Label hint = UiKit.label(I18n.tr("account.signed_out.hint"), "home-sub");
        wrap(hint);
        javafx.scene.control.Hyperlink forgot = new javafx.scene.control.Hyperlink(I18n.tr("account.btn.forgot"));
        forgot.setStyle("-fx-text-fill: #e08a45; -fx-border-color: transparent;");
        forgot.setOnAction(e -> open.accept(resetUrl));
        HBox fields = new HBox(10, f.login, f.password);
        HBox buttons = new HBox(10, f.signIn,
                UiKit.button(I18n.tr("account.btn.register"), null, "button-border", () -> open.accept(registerUrl)));
        buttons.setAlignment(Pos.CENTER_LEFT);
        f.root.getChildren().addAll(hint, fields, forgot, f.plate, buttons);
        if (codeLogin) {
            f.root.getChildren().add(UiKit.button(I18n.tr("account.btn.sign_in_code"), null, "button-border", controller::signIn));
        }
        return f;
    }

    /** The widgets of the password form and the code that paints a {@link SignInUi} state onto them. */
    private static final class SignInForm {
        final VBox root = new VBox(8);
        final TextField login = new TextField();
        final PasswordField password = new PasswordField();
        final Button signIn = UiKit.button(I18n.tr("account.btn.sign_in"), null, "launch-button", () -> { });
        final HBox plate = new HBox(8);
        final Label plateText = UiKit.label("", "notice-text");
        final javafx.scene.control.ProgressIndicator spinner = new javafx.scene.control.ProgressIndicator();
        boolean locked;

        SignInForm() {
            login.setPromptText(I18n.tr("account.login_prompt"));
            login.setPrefWidth(200);
            password.setPromptText(I18n.tr("account.password_prompt"));
            password.setPrefWidth(200);
            plateText.setWrapText(true);
            plateText.setMaxWidth(400);
            plate.setAlignment(Pos.CENTER_LEFT);
            plate.getStyleClass().add("notice-plate");
            plate.setManaged(false);
            plate.setVisible(false);
            spinner.setMaxSize(20, 20);
            spinner.setPrefSize(20, 20);
            spinner.getStyleClass().add("button-spinner");
        }

        void apply(SignInUi.Button button, SignInUi.Notice notice) {
            locked = SignInUi.fieldsLocked(button);
            login.setDisable(locked);
            password.setDisable(locked);
            signIn.setDisable(locked);
            boolean loading = button == SignInUi.Button.LOADING;
            signIn.setText(loading ? I18n.tr("account.signing_in") : I18n.tr("account.btn.sign_in"));
            signIn.setGraphic(loading ? spinner : null);
            login.getStyleClass().remove("field-invalid");
            password.getStyleClass().remove("field-invalid");
            plate.getStyleClass().removeAll("notice-error", "notice-warn", "notice-info");
            boolean show = notice != null;
            plate.setManaged(show);
            plate.setVisible(show);
            if (!show) {
                return;
            }
            plate.getStyleClass().add("notice-" + notice.kind().name().toLowerCase(java.util.Locale.ROOT));
            plateText.setText(I18n.tr(notice.key()));
            plate.getChildren().setAll(Icons.of("icon-info", 16), plateText);
            TextField target = switch (notice.field()) {
                case LOGIN -> login;
                case PASSWORD -> password;
                case NONE -> null;
            };
            if (target != null) {
                target.getStyleClass().add("field-invalid");
                javafx.application.Platform.runLater(target::requestFocus);
            }
        }
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
        label.setPrefWidth(420);
        label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        label.setAlignment(Pos.TOP_LEFT);
        label.setTextAlignment(javafx.scene.text.TextAlignment.LEFT);
        label.setStyle("-fx-text-alignment: left;");
    }

    private void updateCountdown() {
        if (state == null || !state.showsCode()) {
            return;
        }
        long left = state.secondsLeft(clock.getAsLong());
        countdown.setText(I18n.tr("account.left", left / 60, String.format("%02d", left % 60)));
    }
}
