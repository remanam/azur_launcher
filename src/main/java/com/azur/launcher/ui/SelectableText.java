package com.azur.launcher.ui;

import java.util.Objects;
import javafx.scene.control.TextArea;
import javafx.scene.text.Text;

/**
 * Read-only wrapped text that looks like a label but can be selected with the mouse and copied with Ctrl/Cmd+C.
 * Its height follows the wrapped text, so it never shows a scroll bar.
 */
final class SelectableText extends TextArea {

    private static final double PADDING_X = 24;
    private static final double PADDING_Y = 10;
    private static final double DEFAULT_WIDTH = 480;

    private final Text probe = new Text();

    SelectableText(String text, String extraStyleClass) {
        super(Objects.isNull(text) ? "" : text);
        setEditable(false);
        setWrapText(true);
        getStyleClass().add("selectable-text");
        if (Objects.nonNull(extraStyleClass)) {
            getStyleClass().add(extraStyleClass);
        }
        textProperty().addListener(observable -> fit());
        widthProperty().addListener(observable -> fit());
        fontProperty().addListener(observable -> fit());
        fit();
    }

    /** Replaces the text; null means empty. */
    void show(String text) {
        setText(Objects.isNull(text) ? "" : text);
    }

    private void fit() {
        double width = getWidth() > 0 ? getWidth() : (getPrefWidth() > 0 ? getPrefWidth() : DEFAULT_WIDTH);
        probe.setFont(getFont());
        probe.setWrappingWidth(Math.max(40, width - PADDING_X));
        probe.setText(getText().isEmpty() ? " " : getText());
        double height = Math.ceil(probe.getLayoutBounds().getHeight()) + PADDING_Y;
        setMinHeight(height);
        setPrefHeight(height);
        setMaxHeight(height);
    }
}
