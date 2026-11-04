package com.azur.launcher.ui;

import com.azur.launcher.update.UpdateService;

/**
 * What the launcher self-update shows, as plain data (no JavaFX): the header chip, whether Play is blocked and which
 * restart dialog applies. {@link HomePage} and {@link UpdateDialog} only draw it.
 */
record UpdateNotice(Chip chip, String version, boolean required) {

    /** Header chip next to the bell: restart into the staged update, or open the site (the wrapper is too old). */
    enum Chip { NONE, RESTART, SITE }

    /** Result of "Restart now". */
    enum Restart { STARTED, GAME_BUSY, FAILED }

    /** Content of the restart dialog. */
    enum Dialog { ASK, GAME_RUNNING, CLOSE_MANUALLY }

    static final UpdateNotice NONE = new UpdateNotice(Chip.NONE, "", false);

    static UpdateNotice of(UpdateService.Status status) {
        boolean ready = status.kind() == UpdateService.Kind.READY;
        boolean old = status.kind() == UpdateService.Kind.WRAPPER_OLD;
        if (!ready && !old) {
            return NONE;
        }
        String version = ready ? status.manifest().version() : status.manifest().minWrapper();
        return new UpdateNotice(ready ? Chip.RESTART : Chip.SITE, version, status.manifest().required());
    }

    boolean visible() {
        return chip != Chip.NONE;
    }

    /** A required update blocks Play (either kind). */
    boolean blocksPlay() {
        return visible() && required;
    }

    /** The dialog opens by itself once per version (the chip stays for later). */
    boolean opensDialogFor(String shownVersion) {
        return chip == Chip.RESTART && !version.equals(shownVersion);
    }

    static Dialog dialog(boolean gameBusy) {
        return gameBusy ? Dialog.GAME_RUNNING : Dialog.ASK;
    }

    /** Dialog after a click on "Restart now". */
    static Dialog dialogAfter(Restart result) {
        return switch (result) {
            case GAME_BUSY -> Dialog.GAME_RUNNING;
            case FAILED -> Dialog.CLOSE_MANUALLY;
            case STARTED -> Dialog.ASK;
        };
    }
}
