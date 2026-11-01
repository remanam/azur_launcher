package com.azur.launcher.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Message lookup for the UI and user-facing errors.
 *
 * <p>Bundles are {@code /messages_<lang>.properties} (UTF-8). Russian is the default and the fallback for missing
 * keys. Each bundle is loaded once and kept; {@link #tr(String)} does not allocate.</p>
 */
public final class I18n {

    public static final String DEFAULT_LANGUAGE = "ru";
    public static final Set<String> SUPPORTED = Set.of("ru", "en");

    private static final Map<String, Properties> BUNDLES = new ConcurrentHashMap<>();
    private static volatile Properties current = load(DEFAULT_LANGUAGE);

    private I18n() {
    }

    /** Selects the language: an explicit supported choice wins, anything else (including legacy "auto") is Russian. */
    public static void setLanguage(String setting) {
        current = load(resolve(setting));
    }

    /** Pure language resolution, exposed for tests. The system locale is deliberately ignored. */
    public static String resolve(String setting) {
        String wanted = setting == null ? "" : setting.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED.contains(wanted) ? wanted : DEFAULT_LANGUAGE;
    }

    public static String tr(String key) {
        String value = current.getProperty(key);
        if (value == null) {
            value = load(DEFAULT_LANGUAGE).getProperty(key);
        }
        return value == null ? key : value;
    }

    /** Looks up {@code key} and replaces {@code {0}}, {@code {1}}... with the arguments. */
    public static String tr(String key, Object... args) {
        String text = tr(key);
        for (int i = 0; i < args.length; i++) {
            text = text.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return text;
    }

    /** All keys of a bundle, for the consistency test. */
    public static Set<String> keys(String language) {
        return load(language).stringPropertyNames();
    }

    private static Properties load(String language) {
        return BUNDLES.computeIfAbsent(language, lang -> {
            Properties props = new Properties();
            try (InputStream in = I18n.class.getResourceAsStream("/messages_" + lang + ".properties")) {
                if (in != null) {
                    props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read messages_" + lang + ".properties", e);
            }
            return props;
        });
    }
}
