package com.azur.launcher.ui;

import com.azur.launcher.i18n.I18n;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
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
