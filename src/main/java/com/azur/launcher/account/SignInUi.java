package com.azur.launcher.account;

import java.util.Objects;

/**
 * Pure rules of the password sign-in form (no JavaFX): what the «Войти» button shows and how a failure is drawn, so the form
 * stays on ONE screen while a request runs and the window code only paints these values.
 */
public final class SignInUi {

    public enum Button { IDLE, LOADING, ERROR, SUCCESS }

    public enum Kind { ERROR, WARN, INFO }

    public enum Field { LOGIN, PASSWORD, NONE }

    /** A message plate: colour kind, the field to highlight and focus, and the i18n key. */
    public record Notice(Kind kind, Field field, String key) {
    }

    private SignInUi() {
    }

    /** @param submitted the form sent a password request that has not finished */
    public static Button button(AccountFlowState state, boolean submitted) {
        if (state.signedIn()) {
            return Button.SUCCESS;
        }
        if (submitted && state.phase() == AccountFlowState.Phase.STARTING) {
            return Button.LOADING;
        }
        return Objects.nonNull(state.errorKey()) ? Button.ERROR : Button.IDLE;
    }

    public static boolean fieldsLocked(Button button) {
        return button == Button.LOADING || button == Button.SUCCESS;
    }

    /** Null for no message. Unknown keys are drawn as a plain error without a field. */
    public static Notice notice(String errorKey) {
        if (Objects.isNull(errorKey)) {
            return null;
        }
        return switch (errorKey) {
            case "account.err.bad_credentials" -> new Notice(Kind.ERROR, Field.PASSWORD, errorKey);
            case "account.err.fill_both" -> new Notice(Kind.WARN, Field.LOGIN, errorKey);
            case "account.err.not_verified" -> new Notice(Kind.WARN, Field.LOGIN, errorKey);
            case "account.err.too_many_devices", "account.err.rate_limited", "account.err.network" ->
                    new Notice(Kind.WARN, Field.NONE, errorKey);
            case "account.err.password_off" -> new Notice(Kind.INFO, Field.NONE, errorKey);
            default -> new Notice(Kind.ERROR, Field.NONE, errorKey);
        };
    }
}
