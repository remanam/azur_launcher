package com.azur.launcher.ui;

/** Pure mapping from the pack/game state to the one big Home button, and the first-run condition. */
final class HomeState {

    /** What the big button does; {@link #labelKey()} is its i18n key. */
    enum Action {
        START("home.btn.start"), UPDATE("home.btn.update"), UPDATING("home.btn.updating"),
        PLAY("home.btn.play"), RETRY("home.btn.retry");

        private final String labelKey;

        Action(String labelKey) {
            this.labelKey = labelKey;
        }

        String labelKey() {
            return labelKey;
        }
    }

    private HomeState() {
    }

    /**
     * @param installed       the pack/game is installed for the server
     * @param updateAvailable a newer pack exists (supplied by the updater; false when unknown)
     * @param updating        an install or update is running
     * @param failed          the last install or update failed
     */
    static Action action(boolean installed, boolean updateAvailable, boolean updating, boolean failed) {
        if (updating) {
            return Action.UPDATING;
        }
        if (failed) {
            return Action.RETRY;
        }
        if (!installed) {
            return Action.START;
        }
        return updateAvailable ? Action.UPDATE : Action.PLAY;
    }

    /** Progress in whole percent, clamped to 0..100; -1 when the total is unknown. */
    static int percent(long done, long total) {
        if (total <= 0) {
            return -1;
        }
        return (int) Math.max(0, Math.min(100, done * 100 / total));
    }

    /** The first-run wizard is shown only when no account exists yet. */
    static boolean wizardNeeded(boolean hasAccount) {
        return !hasAccount;
    }
}
