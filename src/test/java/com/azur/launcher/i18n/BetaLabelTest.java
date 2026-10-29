package com.azur.launcher.i18n;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Title and version labels live in the shared main resources, so release and debug builds show the same text. */
class BetaLabelTest {

    @AfterEach
    void reset() {
        I18n.setLanguage("ru");
    }

    @Test
    void titleAndVersionSayBetaAndNeverDebug() {
        for (String lang : new String[] {"ru", "en"}) {
            I18n.setLanguage(lang);
            for (String text : new String[] {I18n.tr("app.title"), I18n.tr("app.version", "1.0.0")}) {
                assertTrue(text.contains("(beta)"), lang + ": " + text);
                assertFalse(text.toLowerCase().contains("debug"), lang + ": " + text);
            }
        }
    }
}
