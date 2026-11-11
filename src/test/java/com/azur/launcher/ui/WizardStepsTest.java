package com.azur.launcher.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azur.launcher.i18n.I18n;
import org.junit.jupiter.api.Test;

class WizardStepsTest {

    @Test
    void lastStepReadsNOfN() {
        I18n.setLanguage("ru");
        assertEquals("Шаг 2 из 2", WizardSteps.label(WizardSteps.TOTAL));
        assertEquals("Шаг 1 из 2", WizardSteps.label(1));
        assertEquals("Шаг 2 из 2", WizardSteps.label(3));
        I18n.setLanguage("en");
        assertEquals("Step 2 of 2", WizardSteps.label(2));
    }

    @Test
    void controlButtonLabelsCoverEveryState() {
        // one control button: START/UPDATE/PLAY/RETRY idle labels, UPDATING while busy; cancel/stop come from the phase
        for (HomeState.Action a : HomeState.Action.values()) {
            assertTrue(a.labelKey().startsWith("home.btn."));
        }
        assertEquals(HomeState.Action.UPDATING, HomeState.action(false, false, true, false));
        assertEquals(HomeState.Action.START, HomeState.action(false, false, false, false));
    }
}
