package com.azur.launcher.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azur.launcher.account.AccountFlowState;
import com.azur.launcher.i18n.I18n;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/** Every first-run step must fit the card of a 960x600 window without scrolling (card height = window - chrome - paddings). */
class WizardFitTest {

    private static final double CHROME = 40 + 70 + 120 + 32 + 20; // title bar, header, action bar, middle padding, card padding
    private static final double CARD_WIDTH = 470;

    @Test
    @EnabledIf("com.azur.launcher.ui.CardScrollTest#toolkitAvailable")
    void stepsFitAt960x600() throws Exception {
        double[] heights = new double[2];
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            I18n.setLanguage("ru");
            SiteLoginPanel panel = new SiteLoginPanel(null, "", "", "", false, u -> { }, System::currentTimeMillis);
            panel.show(AccountFlowState.signedOut("account.err.bad_credentials"));
            Label note = UiKit.label(I18n.tr("telemetry.notice"), "option-subtitle");
            note.setWrapText(true);
            note.setMaxWidth(420);
            heights[0] = height(FirstRunWizard.step(1, "wizard.1.title_site", "wizard.1.hint_site", panel.node()));
            heights[1] = height(FirstRunWizard.step(2, "wizard.2.title", "wizard.2.hint", note));
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        double available = 600 - CHROME;
        for (double h : heights) {
            assertTrue(h <= available, "step height " + h + " > " + available);
        }
    }

    private static double height(VBox box) {
        return box.prefHeight(CARD_WIDTH);
    }
}
