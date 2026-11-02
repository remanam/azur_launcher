package com.azur.launcher.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class FieldRulesTest {
    @Test
    void nick() {
        assertEquals("field.err.nick_required", FieldRules.nick("  "));
        assertEquals("field.err.nick", FieldRules.nick("ab"));
        assertEquals("field.err.nick", FieldRules.nick("bad name"));
        assertNull(FieldRules.nick("Azur_1"));
    }

    @Test
    void port() {
        assertNull(FieldRules.port(""));
        assertNull(FieldRules.port("8080"));
        assertEquals("field.err.port", FieldRules.port("0"));
        assertEquals("field.err.port", FieldRules.port("70000"));
        assertEquals("field.err.port", FieldRules.port("abc"));
    }
}
