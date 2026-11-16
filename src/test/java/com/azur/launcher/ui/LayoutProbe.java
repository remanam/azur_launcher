package com.azur.launcher.ui;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Dev tool, not a test: starts the real launcher (use {@code -Dazur.home=<empty temp dir>}) and for several window sizes
 * writes a PNG of the whole window and lists the visible buttons that are cut off by the window or by their card.
 * Run: {@code ../tools/gradle.sh probeLayout -Pprobe=<out dir>}.
 */
public final class LayoutProbe extends Application {

    private static final int[][] SIZES = {{854, 480}, {960, 600}, {1000, 600}, {1040, 720}, {1280, 800}};

    @Override
    public void start(Stage stage) {
        new LauncherApp().start(stage);
        stage.setMinWidth(0);
        stage.setMinHeight(0);
        step(stage, 0);
    }

    private void step(Stage stage, int i) {
        if (i == SIZES.length) {
            Platform.exit();
            System.exit(0);
            return;
        }
        stage.setWidth(SIZES[i][0]);
        stage.setHeight(SIZES[i][1]);
        PauseTransition wait = new PauseTransition(Duration.millis(900));
        wait.setOnFinished(e -> {
            report(stage, SIZES[i][0], SIZES[i][1]);
            step(stage, i + 1);
        });
        wait.play();
    }

    private void report(Stage stage, int w, int h) {
        Parent root = stage.getScene().getRoot();
        File dir = new File(System.getProperty("probe.out", "."));
        dir.mkdirs();
        try {
            WritableImage image = root.snapshot(null, null);
            int iw = (int) image.getWidth();
            int ih = (int) image.getHeight();
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(iw, ih, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < ih; y++) {
                for (int x = 0; x < iw; x++) {
                    out.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                }
            }
            javax.imageio.ImageIO.write(out, "png", new File(dir, "probe_" + w + "x" + h + ".png"));
        } catch (Exception | LinkageError e) {
            System.out.println("[probe] no png: " + e);
        }
        List<String> cut = new ArrayList<>();
        Bounds win = root.localToScene(root.getLayoutBounds());
        collect(root, win, cut);
        System.out.println("[probe] " + w + "x" + h + " scene " + stage.getScene().getWidth() + "x" + stage.getScene().getHeight()
                + " cut buttons: " + cut);
    }

    private void collect(Node node, Bounds win, List<String> out) {
        if (!node.isVisible() || !node.isManaged() && !(node instanceof Button)) {
            return;
        }
        if (node instanceof Button b && b.isVisible() && Objects.nonNull(b.getScene())) {
            Bounds s = b.localToScene(b.getBoundsInLocal());
            boolean inWindow = s.getMinX() >= win.getMinX() && s.getMaxX() <= win.getMaxX()
                    && s.getMinY() >= win.getMinY() && s.getMaxY() <= win.getMaxY();
            Parent p = b.getParent();
            boolean clipped = false;
            while (Objects.nonNull(p)) {
                if (Objects.nonNull(p.getClip()) || p instanceof javafx.scene.control.ScrollPane) {
                    break;
                }
                Bounds pb = p.localToScene(p.getLayoutBounds());
                if (Objects.nonNull(p.getParent()) && (s.getMaxY() > pb.getMaxY() + 1 || s.getMaxX() > pb.getMaxX() + 1)
                        && p.getStyleClass().contains("forge-card")) {
                    clipped = true;
                }
                p = p.getParent();
            }
            if (!inWindow || clipped) {
                out.add("'" + b.getText() + "' y=" + (int) s.getMinY() + ".." + (int) s.getMaxY() + (clipped ? " outside card" : " outside window"));
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, win, out);
            }
        }
    }

    /** Not an Application subclass, so the classpath launch works without the module path. */
    public static final class Main {
        private Main() {
        }

        public static void main(String[] args) {
            Application.launch(LayoutProbe.class, args);
        }
    }
}
