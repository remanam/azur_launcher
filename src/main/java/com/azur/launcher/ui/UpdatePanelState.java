package com.azur.launcher.ui;

import java.util.Objects;
import com.azur.launcher.update.UpdateService;

/**
 * The «Launcher update» block of Settings as plain data (no JavaFX): which line and hint to show and which buttons
 * are enabled. {@link UpdatePanel} only draws it. {@code running} is the version that really runs (the loaded jar).
 */
record UpdatePanelState(Kind kind, String running, String target, String reason, long checkedAtMillis,
                        boolean translocated, boolean gameBusy) {

    enum Kind { NEVER_CHECKED, UP_TO_DATE, READY, WRAPPER_OLD, FAILED }

    static UpdatePanelState of(String running, UpdateService.Status last, long checkedAtMillis, boolean translocated,
                               boolean gameBusy) {
        if (Objects.isNull(last)) {
            return new UpdatePanelState(Kind.NEVER_CHECKED, running, "", "", 0, translocated, gameBusy);
        }
        return switch (last.kind()) {
            case READY -> new UpdatePanelState(Kind.READY, running, last.manifest().version(), "", checkedAtMillis,
                    translocated, gameBusy);
            case WRAPPER_OLD -> new UpdatePanelState(Kind.WRAPPER_OLD, running, last.manifest().minWrapper(), "",
                    checkedAtMillis, translocated, gameBusy);
            case FAILED -> new UpdatePanelState(Kind.FAILED, running, "", Objects.isNull(last.message()) ? "" : last.message(),
                    checkedAtMillis, translocated, gameBusy);
            case UP_TO_DATE, BUSY -> new UpdatePanelState(Kind.UP_TO_DATE, running, "", "", checkedAtMillis,
                    translocated, gameBusy);
        };
    }

    /** i18n key of the status line (argument: {@link #target} or {@link #reason}). */
    String statusKey() {
        return switch (kind) {
            case NEVER_CHECKED -> "updatepanel.never";
            case UP_TO_DATE -> "updatepanel.up_to_date";
            case READY -> "updatepanel.ready";
            case WRAPPER_OLD -> "updatepanel.wrapper_old";
            case FAILED -> "updatepanel.failed";
        };
    }

    String statusArg() {
        return kind == Kind.FAILED ? reason : target;
    }

    boolean canRestart() {
        return kind == Kind.READY && !gameBusy;
    }

    boolean showRestart() {
        return kind == Kind.READY;
    }

    boolean canCheck() {
        return !gameBusy;
    }

    boolean showTranslocationHint() {
        return translocated;
    }
}
