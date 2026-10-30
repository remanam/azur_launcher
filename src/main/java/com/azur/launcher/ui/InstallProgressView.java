package com.azur.launcher.ui;

import com.azur.launcher.i18n.I18n;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Step name with «Шаг n из m», a bar with the whole-run percent next to it, and the numbers line. The bar is our own
 * pane (track, fill, sweep), not a JavaFX ProgressBar: its skin sizes the fill from CSS padding and ignored our style.
 * An indeterminate step (no counters) draws a sweep over the unfilled part, never a made-up percent.
 */
final class InstallProgressView {

    private final Label step = UiKit.label("", "progress-step");
    private final Label stepOf = UiKit.label("", "progress-detail");
    private final Label percent = UiKit.label("0%", "progress-percent");
    private final Track track = new Track();
    private final Label detail = UiKit.label("", "progress-detail");
    private final VBox box;
    private InstallProgress.Snapshot shown;

    InstallProgressView() {
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(8, step, gap, stepOf);
        head.setAlignment(Pos.BASELINE_LEFT);
        percent.setMinWidth(72);
        HBox.setHgrow(track, Priority.ALWAYS);
        HBox barRow = new HBox(12, percent, track);
        barRow.setAlignment(Pos.CENTER_LEFT);
        detail.setWrapText(true);
        box = new VBox(8, head, barRow, detail);
        box.getStyleClass().add("install-progress");
        hide();
    }

    VBox node() {
        return box;
    }

    void show(InstallProgress.Snapshot snapshot) {
        if (snapshot.equals(shown)) {
            return;
        }
        shown = snapshot;
        box.setVisible(true);
        box.setManaged(true);
        boolean done = snapshot.step() == InstallProgress.Step.DONE;
        step.setText(I18n.tr(snapshot.step().key()));
        stepOf.setText(done ? "" : I18n.tr("progress.step_of", snapshot.stepNo(), snapshot.stepCount()));
        percent.setText((int) Math.floor(snapshot.overall() * 100 + 1e-9) + "%");
        track.set(snapshot.overall(), snapshot.indeterminate());
        detail.setText(snapshot.detail());
    }

    void hide() {
        shown = null;
        track.set(0, false);
        box.setVisible(false);
        box.setManaged(false);
    }

    /** Rounded track, azure fill with a gold leading edge and a sweep segment for steps without counters. */
    private static final class Track extends Pane {

        private static final double HEIGHT = 18;
        private static final double SWEEP_SHARE = 0.25;

        private final Region fill = new Region();
        private final Region sweep = new Region();
        private final DoubleProperty phase = new SimpleDoubleProperty();
        private final Timeline sweeper = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(phase, 0)),
                new KeyFrame(Duration.seconds(1.4), new KeyValue(phase, 1)));
        private double value;

        Track() {
            getStyleClass().add("progress-track");
            fill.getStyleClass().add("progress-fill");
            sweep.getStyleClass().add("progress-sweep");
            sweep.setVisible(false);
            sweeper.setCycleCount(Animation.INDEFINITE);
            getChildren().addAll(fill, sweep);
            setMinHeight(HEIGHT);
            setPrefHeight(HEIGHT);
            setMaxHeight(HEIGHT);
            phase.addListener(o -> requestLayout());
        }

        void set(double overall, boolean indeterminate) {
            value = Math.max(0, Math.min(1.0, overall));
            sweep.setVisible(indeterminate);
            if (indeterminate) {
                sweeper.play();
            } else {
                sweeper.stop();
            }
            requestLayout();
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth();
            double fillW = w * value;
            fill.setVisible(fillW >= 1);
            fill.resizeRelocate(0, 0, fillW, HEIGHT);
            double segW = w * SWEEP_SHARE;
            double start = fillW;
            sweep.resizeRelocate(start + phase.get() * Math.max(0, w - segW - start), 0, segW, HEIGHT);
        }
    }
}
