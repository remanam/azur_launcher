package com.azur.launcher.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azur.launcher.ui.HomeState.Action;
import org.junit.jupiter.api.Test;

class HomeStateTest {

    @Test
    void buttonFollowsState() {
        assertEquals(Action.START, HomeState.action(false, false, false, false));
        assertEquals(Action.UPDATE, HomeState.action(true, true, false, false));
        assertEquals(Action.PLAY, HomeState.action(true, false, false, false));
        assertEquals(Action.UPDATING, HomeState.action(true, true, true, false));
        assertEquals(Action.UPDATING, HomeState.action(false, false, true, true));
        assertEquals(Action.RETRY, HomeState.action(false, false, false, true));
        assertEquals(Action.RETRY, HomeState.action(true, true, false, true));
    }

    @Test
    void percentIsClamped() {
        assertEquals(43, HomeState.percent(43, 100));
        assertEquals(100, HomeState.percent(300, 100));
        assertEquals(0, HomeState.percent(-5, 100));
        assertEquals(-1, HomeState.percent(5, 0));
    }

    @Test
    void wizardOnlyWithoutAccount() {
        assertTrue(HomeState.wizardNeeded(false));
        assertFalse(HomeState.wizardNeeded(true));
    }

    @Test
    void installedNeedsPackVersionWhenServerHasPack() {
        assertTrue(HomeState.installed(true, false, ""));
        assertFalse(HomeState.installed(false, false, "1"));
        assertFalse(HomeState.installed(true, true, ""));
        assertFalse(HomeState.installed(true, true, null));
        assertTrue(HomeState.installed(true, true, "1.2"));
    }

    @Test
    void updateOnlyWhenBothVersionsKnownAndDiffer() {
        assertTrue(HomeState.updateAvailable("1.0", "1.1"));
        assertFalse(HomeState.updateAvailable("1.1", "1.1"));
        assertFalse(HomeState.updateAvailable("1.0", ""));
        assertFalse(HomeState.updateAvailable("", "1.1"));
        assertFalse(HomeState.updateAvailable("1.0", null));
    }
}
