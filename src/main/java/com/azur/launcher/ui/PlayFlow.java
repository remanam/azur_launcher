package com.azur.launcher.ui;

import java.util.function.Consumer;

/**
 * The one Play pipeline: game (Minecraft + NeoForge), then the pack (mods and config), then launch (the launch step
 * also finds or downloads Java). A step that is already done is skipped, so Play is the only button the player needs.
 * Pure: the work is injected through {@link Steps}.
 */
final class PlayFlow {

    enum Step { GAME, PACK, LAUNCH }

    /** The work and the "already done" checks of every step. */
    interface Steps {
        boolean gameReady();

        void installGame() throws Exception;

        /** The pack is installed and matches the version the server announces. */
        boolean packCurrent();

        void installPack() throws Exception;

        void launch() throws Exception;
    }

    /** A step failed; {@link #step()} says which one. */
    static final class StepFailed extends Exception {
        private final Step step;

        StepFailed(Step step, Exception cause) {
            super(cause.getMessage(), cause);
            this.step = step;
        }

        Step step() {
            return step;
        }
    }

    private PlayFlow() {
    }

    static void run(Steps steps, Consumer<Step> onStep) throws StepFailed {
        if (!steps.gameReady()) {
            perform(Step.GAME, onStep, steps::installGame);
        }
        if (!steps.packCurrent()) {
            perform(Step.PACK, onStep, steps::installPack);
        }
        perform(Step.LAUNCH, onStep, steps::launch);
    }

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    private static void perform(Step step, Consumer<Step> onStep, Work work) throws StepFailed {
        onStep.accept(step);
        try {
            work.run();
        } catch (Exception e) {
            throw new StepFailed(step, e);
        }
    }
}
