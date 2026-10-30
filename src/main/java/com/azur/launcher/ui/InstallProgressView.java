package com.azur.launcher.ui;

import com.azur.launcher.i18n.I18n;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;

/** Step name, a static bar and the numbers line (percent, MB, speed, ETA). Redraws only when the text or value changes. */
final class InstallProgressView {

    private final Label step = UiKit.label("", "progress-step");
    private final ProgressBar bar = new ProgressBar(0);
    private final Label detail = UiKit.label("", "progress-detail");
    private final VBox box = new VBox(8, step, bar, detail);
    private InstallProgress.Snapshot shown;

    InstallProgressView() {
        box.getStyleClass().add("install-progress");
        box.setAlignment(Pos.CENTER);
        bar.setMaxWidth(Double.MAX_VALUE);
        detail.setWrapText(true);
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
        step.setText(I18n.tr(snapshot.step().key()));
        boolean indeterminate = snapshot.fraction() < 0;
        bar.setProgress(indeterminate ? 1.0 : snapshot.fraction());
        if (indeterminate != bar.getStyleClass().contains("static-indeterminate")) {
            if (indeterminate) {
                bar.getStyleClass().add("static-indeterminate");
            } else {
                bar.getStyleClass().remove("static-indeterminate");
            }
        }
        detail.setText(snapshot.detail());
    }

    void hide() {
        shown = null;
        box.setVisible(false);
        box.setManaged(false);
    }
}
