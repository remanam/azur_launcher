package com.azur.launcher.ui;

import com.azur.launcher.account.AccountFlowState;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.File;

/** Dev tool: PNGs of the sign-in form in the loading and error states. Run: {@code ../tools/gradle.sh probeForm -Pprobe=<dir>}. */
public final class FormProbe extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        SiteLoginPanel err = new SiteLoginPanel(null, "", "", "", false, u -> { }, System::currentTimeMillis);
        SiteLoginPanel busy = new SiteLoginPanel(null, "", "", "", false, u -> { }, System::currentTimeMillis);
        err.show(AccountFlowState.signedOut("account.err.bad_credentials"));
        busy.submitted = true;
        busy.show(AccountFlowState.starting());
        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(16, card(err), card(busy));
        Scene scene = new Scene(row);
        scene.getStylesheets().add(FormProbe.class.getResource("/app.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        File dir = new File(System.getProperty("probe.out", "."));
        dir.mkdirs();
        javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.millis(600));
        wait.setOnFinished(e -> {
            snap(row, new File(dir, "form_states.png"));
            Platform.exit();
            System.exit(0);
        });
        wait.play();
    }

    private static StackPane card(SiteLoginPanel panel) {
        StackPane card = new StackPane(panel.node());
        card.getStyleClass().add("forge-card");
        card.setPrefSize(500, 300);
        card.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        return card;
    }

    private static void snap(javafx.scene.Node card, File file) {
        try {
            javafx.scene.image.WritableImage image = card.snapshot(null, null);
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < out.getHeight(); y++) {
                for (int x = 0; x < out.getWidth(); x++) {
                    out.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                }
            }
            javax.imageio.ImageIO.write(out, "png", file);
        } catch (Exception e) {
            System.out.println("[form] " + e);
        }
    }

    public static final class Main {
        public static void main(String[] args) {
            Application.launch(FormProbe.class, args);
        }
    }
}
