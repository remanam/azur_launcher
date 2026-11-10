package com.azur.launcher.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SignInUiTest {

    @Test
    void buttonStates() {
        assertEquals(SignInUi.Button.IDLE, SignInUi.button(AccountFlowState.signedOut(null), false));
        assertEquals(SignInUi.Button.LOADING, SignInUi.button(AccountFlowState.starting(), true));
        assertEquals(SignInUi.Button.IDLE, SignInUi.button(AccountFlowState.starting(), false));
        assertEquals(SignInUi.Button.ERROR, SignInUi.button(AccountFlowState.signedOut("account.err.bad_credentials"), false));
    }

    @Test
    void loadingLocksFields() {
        assertTrue(SignInUi.fieldsLocked(SignInUi.Button.LOADING));
        assertTrue(SignInUi.fieldsLocked(SignInUi.Button.SUCCESS));
        assertFalse(SignInUi.fieldsLocked(SignInUi.Button.ERROR));
        assertFalse(SignInUi.fieldsLocked(SignInUi.Button.IDLE));
    }

    @Test
    void noticesPickKindAndField() {
        assertNull(SignInUi.notice(null));
        SignInUi.Notice bad = SignInUi.notice("account.err.bad_credentials");
        assertEquals(SignInUi.Kind.ERROR, bad.kind());
        assertEquals(SignInUi.Field.PASSWORD, bad.field());
        assertEquals(SignInUi.Kind.WARN, SignInUi.notice("account.err.rate_limited").kind());
        assertEquals(SignInUi.Field.LOGIN, SignInUi.notice("account.err.not_verified").field());
        assertEquals(SignInUi.Kind.ERROR, SignInUi.notice("account.err.generic").kind());
    }
}
