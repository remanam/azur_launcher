package com.azur.launcher.ui;

import com.azur.launcher.auth.OfflineAccount;

/** Pure input checks: i18n key of the error, or null when the value is fine. */
final class FieldRules {
    private FieldRules() {
    }

    static String nick(String text) {
        String name = text == null ? "" : text.trim();
        if (name.isEmpty()) {
            return "field.err.nick_required";
        }
        return OfflineAccount.isValidName(name) ? null : "field.err.nick";
    }

    /** Empty is allowed (system proxy settings). */
    static String port(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            int port = Integer.parseInt(value);
            return port > 0 && port < 65536 ? null : "field.err.port";
        } catch (NumberFormatException e) {
            return "field.err.port";
        }
    }

    static String required(String text, String key) {
        return text == null || text.isBlank() ? key : null;
    }
}
