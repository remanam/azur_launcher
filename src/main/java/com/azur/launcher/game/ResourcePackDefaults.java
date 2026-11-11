package com.azur.launcher.game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enables the pack's resource packs in {@code options.txt} (soft mode). A marker file {@code azur_resourcepacks.txt}
 * remembers which of our packs were already offered: they are enabled once and never re-enabled after the player removes
 * them. Our packs go before {@code vanilla}, the player's other enabled packs stay as they are. A pack is offered only when
 * its zip is present in {@code resourcepacks/}.
 */
final class ResourcePackDefaults {

    /** Our packs in priority order as in the owner's setup (file names under {@code resourcepacks/}). */
    static final List<String> PACKS = List.of(
            "Faithful 32x - 1.21.1.zip",
            "Upscaled Mekanism 32x v1.3 - [1.20.1-1.21.1].zip",
            "azur_divine_32x.zip");

    static final String MARKER = "azur_resourcepacks.txt";
    private static final String KEY = "resourcePacks:";
    private static final List<String> FRESH_TAIL = List.of("vanilla", "mod_resources");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private ResourcePackDefaults() {
    }

    /** Offered names (installed and not yet in the marker) for this game dir. */
    static List<String> toOffer(Path gameDir) throws IOException {
        Set<String> known = readMarker(gameDir);
        List<String> out = new ArrayList<>();
        for (String name : PACKS) {
            if (!known.contains(name) && Files.isRegularFile(gameDir.resolve("resourcepacks").resolve(name))) {
                out.add(name);
            }
        }
        return out;
    }

    /** Returns {@code text} with the offered packs added to the {@code resourcePacks} line (created when absent). */
    static String update(String text, List<String> offer) {
        if (offer.isEmpty()) {
            return text;
        }
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(KEY)) {
                List<String> list = parse(lines[i].substring(KEY.length()));
                int at = list.indexOf("vanilla");
                at = at < 0 ? list.size() : at;
                for (String name : offer) {
                    String id = "file/" + name;
                    if (!list.contains(id)) {
                        list.add(at++, id);
                    }
                }
                lines[i] = KEY + format(list);
                return String.join(eol, lines);
            }
        }
        List<String> list = new ArrayList<>();
        offer.forEach(n -> list.add("file/" + n));
        list.addAll(FRESH_TAIL);
        StringBuilder out = new StringBuilder(text);
        if (!text.isEmpty() && !text.endsWith("\n")) {
            out.append(eol);
        }
        return out.append(KEY).append(format(list)).append(eol).toString();
    }

    static void saveMarker(Path gameDir, List<String> offered) throws IOException {
        if (offered.isEmpty()) {
            return;
        }
        Set<String> all = readMarker(gameDir);
        all.addAll(offered);
        Files.writeString(gameDir.resolve(MARKER), String.join("\n", all) + "\n", StandardCharsets.UTF_8);
    }

    private static Set<String> readMarker(Path gameDir) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Path f = gameDir.resolve(MARKER);
        if (Files.exists(f)) {
            for (String l : Files.readString(f, StandardCharsets.UTF_8).split("\r?\n")) {
                if (!l.isBlank()) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    static List<String> parse(String json) {
        List<String> out = new ArrayList<>();
        Matcher m = STRING.matcher(json);
        while (m.find()) {
            out.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return out;
    }

    static String format(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            sb.append(i == 0 ? "" : ",").append('"')
                    .append(list.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }
}
