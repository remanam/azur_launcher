package com.azur.launcher.ui;

import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.VBox;

/**
 * Field + one reserved message line under it (no layout jump). Error = red border (.field-invalid) and text in
 * .field-error; typing clears it. Spec: docs/design/FORM_ERRORS.md.
 */
public final class FieldError {
    private final TextInputControl field;
    private final Label message = new Label();
    private final VBox box;

    public FieldError(TextInputControl field) {
        this.field = field;
        message.getStyleClass().add("field-msg");
        message.setWrapText(true);
        field.textProperty().addListener((obs, old, text) -> clear());
        box = new VBox(4, field, message);
    }

    /** The field with its message line; put this into the layout instead of the bare field. */
    public VBox node() {
        return box;
    }

    /** Shows {@code text} under the field (already localized); null or empty clears. */
    public void show(String text) {
        boolean bad = text != null && !text.isEmpty();
        message.setText(bad ? text : "");
        message.getStyleClass().remove("field-error");
        field.getStyleClass().remove("field-invalid");
        if (bad) {
            message.getStyleClass().add("field-error");
            field.getStyleClass().add("field-invalid");
        }
    }

    public String text() {
        return field.getText();
    }

    public void clear() {
        show(null);
    }

    /** Returns true when valid; otherwise shows the message of {@code errorKey} (null = valid). */
    public boolean check(String errorKey) {
        show(errorKey == null ? null : com.azur.launcher.i18n.I18n.tr(errorKey));
        return errorKey == null;
    }
}
