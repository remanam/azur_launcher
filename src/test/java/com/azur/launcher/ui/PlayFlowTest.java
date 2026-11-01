package com.azur.launcher.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlayFlowTest {

    private static final class Fake implements PlayFlow.Steps {
        final List<String> calls = new ArrayList<>();
        boolean gameReady;
        boolean packCurrent;
        String failAt = "";

        private void work(String name) throws Exception {
            calls.add(name);
            if (name.equals(failAt)) {
                throw new java.io.IOException("boom " + name);
            }
        }

        @Override public boolean gameReady() { return gameReady; }
        @Override public void installGame() throws Exception { work("game"); }
        @Override public boolean packCurrent() { return packCurrent; }
        @Override public void installPack() throws Exception { work("pack"); }
        @Override public void launch() throws Exception { work("launch"); }
    }

    @Test
    void firstRunInstallsGameThenPackThenLaunches() throws Exception {
        Fake f = new Fake();
        List<PlayFlow.Step> seen = new ArrayList<>();
        PlayFlow.run(f, seen::add);
        assertEquals(List.of("game", "pack", "launch"), f.calls);
        assertEquals(List.of(PlayFlow.Step.GAME, PlayFlow.Step.PACK, PlayFlow.Step.LAUNCH), seen);
    }

    @Test
    void doneStepsAreSkipped() throws Exception {
        Fake f = new Fake();
        f.gameReady = true;
        f.packCurrent = true;
        PlayFlow.run(f, s -> { });
        assertEquals(List.of("launch"), f.calls);
    }

    @Test
    void newPackVersionUpdatesOnlyThePackBeforeLaunch() throws Exception {
        Fake f = new Fake();
        f.gameReady = true;
        PlayFlow.run(f, s -> { });
        assertEquals(List.of("pack", "launch"), f.calls);
    }

    @Test
    void failureOnEachStepStopsTheFlowAndNamesTheStep() {
        for (PlayFlow.Step step : PlayFlow.Step.values()) {
            Fake f = new Fake();
            f.failAt = step.name().toLowerCase();
            PlayFlow.StepFailed e = assertThrows(PlayFlow.StepFailed.class, () -> PlayFlow.run(f, s -> { }));
            assertEquals(step, e.step());
            assertEquals("boom " + f.failAt, e.getMessage());
            assertEquals(f.failAt, f.calls.get(f.calls.size() - 1));
        }
    }
}
