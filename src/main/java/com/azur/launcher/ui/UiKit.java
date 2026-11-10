package com.azur.launcher.ui;

import com.azur.launcher.i18n.I18n;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.net.URL;
import java.util.function.Supplier;

/** Small factory methods for the CSS-styled building blocks used by every page. */
final class UiKit {

    private UiKit() {
    }

    static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    /** Selectable, copyable read-only text in the look of a label (errors, details, results). */
    static SelectableText selectable(String text, String styleClass) {
        return new SelectableText(text, styleClass);
    }

    /** Button that puts the text from {@code source} into the system clipboard. */
    static Button copyButton(Supplier<String> source) {
        return button(I18n.tr("common.copy"), null, "button-border", () -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(source.get());
            Clipboard.getSystemClipboard().setContent(content);
        });
    }

    /** The text with a «Copy» button under it; the whole block is hidden while the text is empty. */
    static VBox withCopy(SelectableText text) {
        VBox box = new VBox(6, text, copyButton(text::getText));
        box.setAlignment(Pos.CENTER_LEFT);
        box.visibleProperty().bind(text.textProperty().isNotEmpty());
        box.managedProperty().bind(box.visibleProperty());
        return box;
    }

    /** Error dialog whose text can be selected and copied. */
    static void errorDialog(Window owner, String message, boolean dark) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setTitle(I18n.tr("common.error"));
        alert.setHeaderText(I18n.tr("common.error"));
        SelectableText text = selectable(message, null);
        text.setPrefWidth(460);
        VBox content = new VBox(10, text, copyButton(() -> message));
        content.setAlignment(Pos.CENTER_LEFT);
        alert.getDialogPane().setContent(content);
        styleDialog(alert.getDialogPane(), dark);
        alert.showAndWait();
    }

    /** Game crash dialog: exit code, last output lines (selectable, copyable) and «Open logs folder». */
    static void gameExitDialog(Window owner, int code, String output, Runnable openLogs, boolean dark) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setTitle(I18n.tr("common.error"));
        alert.setHeaderText(I18n.tr("home.exited", code));
        SelectableText text = selectable(output.isBlank() ? I18n.tr("play.no_output") : output, null);
        text.setPrefWidth(560);
        text.setMaxHeight(320);
        Button open = button(I18n.tr("play.open_logs"), null, "button-border", openLogs);
        HBox buttons = new HBox(8, copyButton(() -> output), open);
        VBox content = new VBox(10, text, buttons);
        content.setAlignment(Pos.CENTER_LEFT);
        alert.getDialogPane().setContent(content);
        styleDialog(alert.getDialogPane(), dark);
        alert.showAndWait();
    }

    /** Hang dialog: diagnosis + dump head, Copy / Open logs; true = terminate, false = wait. Auto-terminates after 60 s. */
    static boolean hangDialog(Window owner, String message, String dumpHead, Runnable openLogs, boolean dark) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setTitle(I18n.tr("hang.title"));
        alert.setHeaderText(I18n.tr("hang.title"));
        String full = message + "\n\n" + dumpHead;
        SelectableText text = selectable(full, null);
        text.setPrefWidth(560);
        text.setMaxHeight(320);
        Button open = button(I18n.tr("play.open_logs"), null, "button-border", openLogs);
        Label auto = label(I18n.tr("hang.auto"), null);
        VBox content = new VBox(10, text, new HBox(8, copyButton(() -> full), open), auto);
        alert.getDialogPane().setContent(content);
        ButtonType kill = new ButtonType(I18n.tr("hang.terminate"), ButtonBar.ButtonData.OK_DONE);
        ButtonType wait = new ButtonType(I18n.tr("hang.wait"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(kill, wait);
        styleDialog(alert.getDialogPane(), dark);
        javafx.animation.PauseTransition timer = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(60));
        timer.setOnFinished(e -> alert.setResult(kill));
        timer.play();
        boolean result = alert.showAndWait().orElse(kill) == kill;
        timer.stop();
        return result;
    }

    static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    static Button button(String text, String icon, String styleClass, Runnable action) {
        Button button = new Button(text);
        if (icon != null) {
            button.setGraphic(Icons.of(icon, 14));
        }
        button.getStyleClass().add(styleClass);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setMaxWidth(Region.USE_PREF_SIZE);
        button.setOnAction(event -> action.run());
        return button;
    }

    static VBox card(Node... children) {
        VBox card = new VBox(10, children);
        card.getStyleClass().add("card");
        return card;
    }

    static HBox cardHeader(String icon, String title, Node... right) {
        HBox header = new HBox(8, Icons.of(icon, 16), label(title, "card-title"), spacer());
        header.getChildren().addAll(right);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    static HBox optionItem(String title, String subtitle, Node... controls) {
        Label subtitleLabel = label(subtitle, "option-subtitle");
        subtitleLabel.setWrapText(true);
        VBox text = new VBox(2, label(title, "option-title"), subtitleLabel);
        text.setMinWidth(200);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox item = new HBox(12, text);
        item.getStyleClass().add("options-list-item");
        item.setAlignment(Pos.CENTER_LEFT);
        item.getChildren().addAll(controls);
        return item;
    }

    static ScrollPane pageScroll(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("page-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    /**
     * Wraps a scroll pane with a soft gradient at the bottom edge while content is hidden below the view (a hint that the
     * form scrolls), and scrolls to the focused control on Tab. The wrapper is mouse-transparent over the hint.
     */
    static StackPane withScrollHint(ScrollPane scroll) {
        Region fade = new Region();
        fade.getStyleClass().add("card-scroll-more");
        fade.setMouseTransparent(true);
        fade.setPrefHeight(34);
        fade.setMaxHeight(34);
        StackPane.setAlignment(fade, Pos.BOTTOM_CENTER);
        fade.setVisible(false);
        Runnable update = () -> {
            Node content = scroll.getContent();
            boolean more = content != null && content.getLayoutBounds().getHeight() > scroll.getViewportBounds().getHeight() + 1
                    && scroll.getVvalue() < scroll.getVmax() - 0.01;
            fade.setVisible(more);
        };
        scroll.vvalueProperty().addListener((o, a, b) -> update.run());
        scroll.viewportBoundsProperty().addListener((o, a, b) -> update.run());
        scroll.contentProperty().addListener((o, a, b) -> update.run());
        scroll.sceneProperty().addListener((o, old, scene) -> {
            if (scene != null) {
                scene.focusOwnerProperty().addListener((obs, was, now) -> scrollTo(scroll, now));
            }
        });
        scroll.needsLayoutProperty().addListener((o, a, b) -> update.run());
        return new StackPane(scroll, fade);
    }

    private static void scrollTo(ScrollPane scroll, Node focused) {
        Node content = scroll.getContent();
        if (focused == null || content == null) {
            return;
        }
        for (Node n = focused; n != null; n = n.getParent()) {
            if (n == content) {
                javafx.geometry.Bounds b = content.sceneToLocal(focused.localToScene(focused.getBoundsInLocal()));
                double extra = content.getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
                if (extra > 0) {
                    double top = scroll.getVvalue() * extra;
                    double view = scroll.getViewportBounds().getHeight();
                    if (b.getMinY() < top) {
                        scroll.setVvalue(Math.max(0, b.getMinY() - 8) / extra);
                    } else if (b.getMaxY() > top + view) {
                        scroll.setVvalue(Math.min(extra, b.getMaxY() + 8 - view) / extra);
                    }
                }
                return;
            }
        }
    }

    static StackPane avatar(String text, String boxClass, String textClass) {
        String initial = text == null || text.isBlank() ? "?" : text.substring(0, 1).toUpperCase();
        StackPane avatar = new StackPane(label(initial, textClass));
        avatar.getStyleClass().add(boxClass);
        return avatar;
    }

    /** Gives a dialog the launcher stylesheet and the current theme. */
    static void styleDialog(DialogPane pane, boolean dark) {
        pane.setMinWidth(480);
        pane.setMaxWidth(600);
        URL stylesheet = UiKit.class.getResource("/app.css");
        if (stylesheet != null) {
            pane.getStylesheets().add(stylesheet.toExternalForm());
        }
        if (dark) {
            pane.getStyleClass().add("dark");
        }
    }
}
