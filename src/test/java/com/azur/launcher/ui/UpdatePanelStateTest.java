package com.azur.launcher.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azur.launcher.update.UpdateManifest;
import com.azur.launcher.update.UpdateService;

import org.junit.jupiter.api.Test;

class UpdatePanelStateTest {

    private static UpdateService.Status status(UpdateService.Kind kind, String message) {
        UpdateManifest m = new UpdateManifest("1.0.11", "00", 1, "2026-11-10", "https://example.test/dl/a.jar", "1.0.0", false, "");
        return new UpdateService.Status(kind, kind == UpdateService.Kind.FAILED || kind == UpdateService.Kind.BUSY ? null : m, message);
    }

    private static UpdatePanelState of(UpdateService.Status s, boolean busy) {
        return UpdatePanelState.of("1.0.10", s, 5, false, busy);
    }

    @Test
    void neverCheckedHasNoRestart() {
        UpdatePanelState st = UpdatePanelState.of("1.0.10", null, 0, false, false);
        assertEquals("updatepanel.never", st.statusKey());
        assertFalse(st.showRestart());
        assertTrue(st.canCheck());
    }

    @Test
    void readyOffersRestartUnlessTheGameRuns() {
        UpdatePanelState st = of(status(UpdateService.Kind.READY, ""), false);
        assertEquals("updatepanel.ready", st.statusKey());
        assertEquals("1.0.11", st.statusArg());
        assertTrue(st.canRestart());
        UpdatePanelState busy = of(status(UpdateService.Kind.READY, ""), true);
        assertTrue(busy.showRestart());
        assertFalse(busy.canRestart());
        assertFalse(busy.canCheck());
    }

    @Test
    void failedShowsTheReason() {
        UpdatePanelState st = of(status(UpdateService.Kind.FAILED, "no network"), false);
        assertEquals("updatepanel.failed", st.statusKey());
        assertEquals("no network", st.statusArg());
        assertFalse(st.showRestart());
    }

    @Test
    void upToDateAndBusyKeepThePreviousLine() {
        assertEquals("updatepanel.up_to_date", of(status(UpdateService.Kind.UP_TO_DATE, ""), false).statusKey());
        assertEquals("updatepanel.up_to_date", of(status(UpdateService.Kind.BUSY, ""), false).statusKey());
    }

    @Test
    void translocationHintFollowsTheFlag() {
        assertTrue(UpdatePanelState.of("1.0.10", null, 0, true, false).showTranslocationHint());
        assertFalse(of(status(UpdateService.Kind.UP_TO_DATE, ""), false).showTranslocationHint());
    }
}
