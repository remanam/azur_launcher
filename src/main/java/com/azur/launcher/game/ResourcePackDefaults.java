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
 * them. In {@code options.txt} a LATER entry overrides an earlier one (Minecraft FallbackResourceManager.getResource walks the
 * list from the end; PackRepository.rebuildSelected keeps a listed {@code vanilla} where it is), so our packs go right after
 * {@code vanilla}/{@code mod_resources} (above them), never before. Older launchers put them before {@code vanilla}, where
 * vanilla overrode them: such entries are moved once (idempotent). The player's other packs stay as they are. A pack is
 * offered only when its zip is present in {@code resourcepacks/}.
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

    /** Returns {@code text} with the offered packs added (and misplaced ones repaired) in the {@code resourcePacks} line. */
    static String update(String text, List<String> offer) {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(KEY)) {
                List<String> list = parse(lines[i].substring(KEY.length()));
                repair(list);
                for (String name : offer) {
                    String id = "file/" + name;
                    if (!list.contains(id)) {
                        list.add(insertAt(list, true), id);
                    }
                }
                lines[i] = KEY + format(list);
                return String.join(eol, lines);
            }
        }
        if (offer.isEmpty()) {
            return text;
        }
        List<String> list = new ArrayList<>(FRESH_TAIL);
        offer.forEach(n -> list.add("file/" + n));
        StringBuilder out = new StringBuilder(text);
        if (!text.isEmpty() && !text.endsWith("\n")) {
            out.append(eol);
        }
        return out.append(KEY).append(format(list)).append(eol).toString();
    }

    /** Index right after the last of vanilla/mod_resources/our packs; the list end when neither is present. */
    private static int insertAt(List<String> list, boolean withOurs) {
        int at = -1;
        for (String base : FRESH_TAIL) {
            at = Math.max(at, list.indexOf(base));
        }
        for (String name : withOurs ? PACKS : List.<String>of()) {
            at = Math.max(at, list.indexOf("file/" + name));
        }
        return at < 0 ? list.size() : at + 1;
    }

    /** Moves our packs that stand before {@code vanilla} (overridden by it) to the correct place, order kept. */
    private static void repair(List<String> list) {
        int vanilla = list.indexOf("vanilla");
        List<String> moved = new ArrayList<>();
        for (int i = Math.max(vanilla, 0) - 1; i >= 0; i--) {
            String id = list.get(i);
            if (PACKS.stream().anyMatch(n -> ("file/" + n).equals(id))) {
                moved.add(0, list.remove(i));
            }
        }
        list.addAll(insertAt(list, false), moved);
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
