package com.azur.launcher.game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * First-run defaults for the game's {@code options.txt}: only the keys that are MISSING are appended (the game language
 * from the launcher language, no accessibility onboarding screen, narrator off) and our resource packs (see {@link ResourcePackDefaults}). Existing lines and line endings stay.
 */
public final class OptionsDefaults {

    private OptionsDefaults() {
    }

    /** @param launcherLanguage {@code ru} or {@code en} (see {@code I18n.resolve}) */
    public static void apply(Path gameDir, String launcherLanguage) throws IOException {
        Path file = gameDir.resolve("options.txt");
        String text = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        Map<String, String> wanted = new LinkedHashMap<>();
        wanted.put("lang", "ru".equals(launcherLanguage) ? "ru_ru" : "en_us");
        wanted.put("onboardAccessibility", "false");
        wanted.put("narrator", "0");
        wanted.put("tutorialStep", "none"); // vanilla tutorial toasts (movement, inventory, crafting) are noise in our onboarding; only for NEW options.txt keys
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            if (!hasKey(text, e.getKey())) {
                missing.add(e.getKey() + ":" + e.getValue());
            }
        }
        List<String> offer = ResourcePackDefaults.toOffer(gameDir);
        if (missing.isEmpty() && offer.isEmpty() && ResourcePackDefaults.update(text, offer).equals(text)) {
            return;
        }
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder out = new StringBuilder(text);
        if (!text.isEmpty() && !text.endsWith("\n")) {
            out.append(eol);
        }
        for (String line : missing) {
            out.append(line).append(eol);
        }
        String result = ResourcePackDefaults.update(out.toString(), offer);
        Files.createDirectories(gameDir);
        Path tmp = gameDir.resolve("options.txt.azur-tmp");
        Files.writeString(tmp, result, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        ResourcePackDefaults.saveMarker(gameDir, offer);
    }

    private static boolean hasKey(String text, String key) {
        for (String line : text.split("\r?\n")) {
            if (line.startsWith(key + ":")) {
                return true;
            }
        }
        return false;
    }
}
