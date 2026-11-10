package com.azur.launcher.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.application.Platform;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Window-size matrix for the Home card (1.0.9/1.0.10 bug: a card taller than the window pushed the header and the Play
 * button out of it). A tall card inside the scroll pane must not raise the minimum height of the screen.
 */
class CardScrollTest {

    private static final int[][] WINDOWS = {{854, 480}, {960, 600}, {1000, 600}, {1040, 720}, {1280, 800}};
    private static final double CHROME = 40 + 70 + 120; // title bar + header + action bar, as in the real window

    static boolean toolkitAvailable() {
        try {
            CountDownLatch up = new CountDownLatch(1);
            try {
                Platform.startup(up::countDown);
            } catch (IllegalStateException already) {
                up.countDown();
            }
            return up.await(5, TimeUnit.SECONDS);
        } catch (Throwable e) {
            return false;
        }
    }

    @Test
    @EnabledIf("toolkitAvailable")
    void tallCardDoesNotGrowTheScreenBeyondAnyWindow() throws Exception {
        double[] minHeight = new double[1];
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            VBox tall = new VBox();
            for (int i = 0; i < 12; i++) {
                Region row = new Region();
                row.setMinHeight(60);
                row.setPrefHeight(60);
                tall.getChildren().add(row);
            }
            ScrollPane scroll = UiKit.pageScroll(tall);
            scroll.setFitToHeight(true);
            scroll.setMinHeight(0);
            BorderPane screen = new BorderPane();
            screen.setCenter(scroll);
            minHeight[0] = screen.minHeight(-1);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        for (int[] window : WINDOWS) {
            assertTrue(minHeight[0] + CHROME <= window[1], "screen min height " + minHeight[0] + " in " + window[0] + "x" + window[1]);
        }
    }
}
