package com.azur.launcher.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azur.launcher.update.UpdateManifest;
import com.azur.launcher.update.UpdateService;

import org.junit.jupiter.api.Test;

class UpdateNoticeTest {

    private static UpdateManifest manifest(boolean required) {
        return new UpdateManifest("1.0.5", "00", 1, "2026-11-04", "https://example.test/dl/a.jar", "1.0.0", required, "");
    }

    private static UpdateNotice of(UpdateService.Kind kind, boolean required) {
        return UpdateNotice.of(new UpdateService.Status(kind, kind == UpdateService.Kind.UP_TO_DATE ? null : manifest(required), ""));
    }

    @Test
    void readyShowsTheRestartChipAndDoesNotBlockPlayUnlessRequired() {
        UpdateNotice notice = of(UpdateService.Kind.READY, false);
        assertEquals(UpdateNotice.Chip.RESTART, notice.chip());
        assertEquals("1.0.5", notice.version());
        assertFalse(notice.blocksPlay());
        assertTrue(of(UpdateService.Kind.READY, true).blocksPlay());
    }

    @Test
    void oldWrapperShowsTheSiteChip() {
        UpdateNotice notice = of(UpdateService.Kind.WRAPPER_OLD, true);
        assertEquals(UpdateNotice.Chip.SITE, notice.chip());
        assertTrue(notice.blocksPlay());
        assertFalse(notice.opensDialogFor(""));
    }

    @Test
    void otherStatesShowNothing() {
        for (UpdateService.Kind kind : new UpdateService.Kind[] {UpdateService.Kind.UP_TO_DATE}) {
            assertFalse(of(kind, false).visible());
        }
        assertFalse(UpdateNotice.NONE.blocksPlay());
    }

    @Test
    void dialogOpensOncePerVersion() {
        UpdateNotice notice = of(UpdateService.Kind.READY, false);
        assertTrue(notice.opensDialogFor(""));
        assertFalse(notice.opensDialogFor("1.0.5"));
        assertTrue(notice.opensDialogFor("1.0.4"));
    }

    @Test
    void dialogViewFollowsGameAndRestartResult() {
        assertEquals(UpdateNotice.Dialog.GAME_RUNNING, UpdateNotice.dialog(true));
        assertEquals(UpdateNotice.Dialog.ASK, UpdateNotice.dialog(false));
        assertEquals(UpdateNotice.Dialog.CLOSE_MANUALLY, UpdateNotice.dialogAfter(UpdateNotice.Restart.FAILED));
        assertEquals(UpdateNotice.Dialog.GAME_RUNNING, UpdateNotice.dialogAfter(UpdateNotice.Restart.GAME_BUSY));
    }
}
