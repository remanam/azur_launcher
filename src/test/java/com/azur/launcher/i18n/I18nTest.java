package com.azur.launcher.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class I18nTest {

    @AfterEach
    void reset() {
        I18n.setLanguage("ru");
    }

    @Test
    void everyRussianKeyExistsInEnglishAndViceVersa() {
        Set<String> ru = I18n.keys("ru");
        Set<String> en = I18n.keys("en");
        Set<String> missingInEn = new HashSet<>(ru);
        missingInEn.removeAll(en);
        Set<String> missingInRu = new HashSet<>(en);
        missingInRu.removeAll(ru);
        assertTrue(missingInEn.isEmpty(), "keys missing in messages_en: " + missingInEn);
        assertTrue(missingInRu.isEmpty(), "keys missing in messages_ru: " + missingInRu);
        assertFalse(ru.isEmpty());
    }

    @Test
    void placeholdersMatchBetweenLanguages() {
        Pattern placeholder = Pattern.compile("\\{\\d}");
        for (String key : I18n.keys("ru")) {
            I18n.setLanguage("ru");
            Set<String> ruArgs = placeholders(placeholder, I18n.tr(key));
            I18n.setLanguage("en");
            Set<String> enArgs = placeholders(placeholder, I18n.tr(key));
            assertEquals(ruArgs, enArgs, "placeholders differ for " + key);
        }
    }

    @Test
    void everyKeyUsedInSourcesExists() throws IOException {
        Pattern call = Pattern.compile("I18n\\.tr\\(\"([^\"]+)\"");
        Set<String> known = I18n.keys("ru");
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = call.matcher(Files.readString(file));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    // keys ending in a dot or underscore are completed at run time (language, XErr code)
                    if (!key.endsWith(".") && !key.endsWith("_")) {
                        assertTrue(known.contains(key), file.getFileName() + " uses unknown key " + key);
                    }
                }
            }
        }
    }

    @Test
    void trReplacesArgumentsAndFallsBackToTheKey() {
        I18n.setLanguage("en");
        assertEquals("Hello, Alex", I18n.tr("home.greeting", "Alex"));
        assertEquals("no.such.key", I18n.tr("no.such.key"));
    }

    @Test
    void languageResolution() {
        assertEquals("en", I18n.resolve("en", Locale.GERMAN));
        assertEquals("ru", I18n.resolve("ru", Locale.ENGLISH));
        assertEquals("en", I18n.resolve("auto", Locale.ENGLISH));
        assertEquals("ru", I18n.resolve("auto", Locale.forLanguageTag("ru-RU")));
        assertEquals("ru", I18n.resolve("auto", Locale.CHINESE));
        assertEquals("ru", I18n.resolve(null, null));
    }

    private static Set<String> placeholders(Pattern pattern, String text) {
        Set<String> found = new HashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }
}
