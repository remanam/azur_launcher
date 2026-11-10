package com.azur.launcher.ui;

import com.azur.launcher.account.AccountController;
import com.azur.launcher.account.AccountFlowState;
import com.azur.launcher.account.SiteApi;
import com.azur.launcher.account.SiteAuth;
import com.azur.launcher.account.SessionStorage;
import com.azur.launcher.account.SiteSession;
import com.azur.launcher.i18n.I18n;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * Dev tool, not a test: draws the sign-in form (nick/e-mail + password) and the account (signed in) blocks side by side with the real stylesheet
 * and writes one PNG ({@code -Dsnapshot.out=...}). Run: {@code ../tools/gradle.sh snapshotAccount}. No window is opened.
 */
public final class AccountSnapshot {
    private AccountSnapshot() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                render(new File(System.getProperty("snapshot.out", "account.png")));
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
    }

    private static java.awt.image.BufferedImage toBuffered(WritableImage image) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, image.getPixelReader().getArgb(x, y));
            }
        }
        return out;
    }

    private static void render(File out) throws Exception {
        for (String font : new String[] {"philosopher_bold", "pt_sans_web_regular", "pt_sans_web_bold"}) {
            Font.loadFont(AccountSnapshot.class.getResource("/fonts/" + font + ".ttf").toExternalForm(), 14);
        }
        I18n.setLanguage("ru");
        SiteApi api = new SiteApi() {
            public Polled login(String l, char[] p, String n, String k, String lang) {
                return new Polled(false, "", "", "");
            }

            public Started start(String n, String k) {
                return new Started("dc", "K7QM-X2PD", "3fa9-0c71-b8e2-44d6", "https://example.test/link?code=K7QMX2PD", 600, 3);
            }

            public Polled poll(String c) {
                return new Polled(false, "", "", "");
            }

            public Ticket ticket(String d, String r, String s) {
                return null;
            }

            public String rotate(String d, String r, String k, String p) {
                return "";
            }
        };
        long now = System.currentTimeMillis();
        SessionStorage empty = new SessionStorage() {
            public Optional<SiteSession> read() {
                return Optional.empty();
            }

            public void write(SiteSession s) {
            }

            public void clear() {
            }
        };
        SiteAuth auth = new SiteAuth(api, empty, () -> now, ms -> { });
        AccountController controller = new AccountController(auth, Runnable::run, s -> { }, u -> { }, () -> "PC", s -> { });

        SiteLoginPanel waiting = new SiteLoginPanel(controller, "https://example.test/account", "https://example.test/register", "https://example.test/reset", true, u -> { }, () -> now + 31_000);
        waiting.show(AccountFlowState.waiting(auth.begin("Азур Лаунчер (PC)")));

        com.azur.launcher.account.DeviceKey key = com.azur.launcher.account.DeviceKey.generate();
        SiteSession session = new SiteSession("d1", "r", "art", key.seedBase64Url(), key.publicKeyBase64Url(),
                "Азур Лаунчер (MacBook)", now - 3L * 24 * 3600 * 1000, 0);
        SiteLoginPanel signedIn = new SiteLoginPanel(controller, "https://example.test/account", "https://example.test/register", "https://example.test/reset", true, u -> { }, () -> now);
        signedIn.show(AccountFlowState.signedIn(session));

        SiteLoginPanel form = new SiteLoginPanel(controller, "https://example.test/account", "https://example.test/register", "https://example.test/reset", true, u -> { }, () -> now);
        form.show(AccountFlowState.signedOut(null));
        VBox left = UiKit.card(UiKit.label(I18n.tr("wizard.1.title_site"), "card-title"), form.node());
        VBox right = UiKit.card(UiKit.label(I18n.tr("account.title"), "card-title"), signedIn.node());
        left.setPrefWidth(480);
        right.setPrefWidth(480);
        HBox row = new HBox(20, left, right);
        row.setPadding(new Insets(20));
        Scene scene = new Scene(row, 1040, 560, Color.web("#14110f"));
        scene.getStylesheets().add(AccountSnapshot.class.getResource("/app.css").toExternalForm());
        WritableImage image = scene.snapshot(null);
        javax.imageio.ImageIO.write(toBuffered(image), "png", out);
        System.out.println("snapshot written: " + out + " " + (int) image.getWidth() + "x" + (int) image.getHeight());
    }
}
