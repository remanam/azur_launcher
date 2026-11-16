package com.azur.launcher.loader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Pure builder of the single install list: Vanilla releases and NeoForge builds, newest Minecraft first. */
public final class InstallEntries {

    /** NeoForge version as published on the Maven: {@code 21.1.264}, {@code 20.4.0-beta}. */
    private static final Pattern NEOFORGE = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,5}(-[A-Za-z0-9.]{1,24})?");
    private static final int PER_MC_LIMIT = 40;

    private InstallEntries() {
    }

    /** One installable item; {@code neoforge} is null for Vanilla. */
    public record Entry(String mc, String neoforge, boolean pinned) {

        public boolean isVanilla() {
            return Objects.isNull(neoforge);
        }
    }

    public static boolean isValidNeoForge(String version) {
        return Objects.nonNull(version) && NEOFORGE.matcher(version).matches() && Objects.nonNull(mcFor(version));
    }

    /** {@code 21.1.264 -> 1.21.1}, {@code 21.0.5 -> 1.21}; null for builds before 20.2 or a bad format. */
    public static String mcFor(String neoForgeVersion) {
        if (Objects.isNull(neoForgeVersion) || !NEOFORGE.matcher(neoForgeVersion).matches()) {
            return null;
        }
        String[] p = neoForgeVersion.split("[.\\-]");
        int minor = Integer.parseInt(p[0]);
        int patch = Integer.parseInt(p[1]);
        if (minor < 20 || (minor == 20 && patch < 2)) {
            return null;
        }
        return patch == 0 ? "1." + minor : "1." + minor + "." + patch;
    }

    /**
     * The combined list: the pinned entry first (if given), then per Minecraft version (newest first) the
     * Vanilla release followed by up to {@value #PER_MC_LIMIT} newest NeoForge builds. Vanilla releases keep
     * their input order; the pinned build is not repeated.
     */
    public static List<Entry> build(List<String> vanillaReleases, List<String> neoForgeVersions, String pinnedMc,
                                    String pinnedNeoForge) {
        List<Entry> out = new ArrayList<>();
        if (Objects.nonNull(pinnedMc) && Objects.nonNull(pinnedNeoForge)) {
            out.add(new Entry(pinnedMc, pinnedNeoForge, true));
        }
        List<String> mcOrder = new ArrayList<>(vanillaReleases);
        List<String> builds = new ArrayList<>();
        for (String v : neoForgeVersions) {
            String mc = mcFor(v);
            if (Objects.nonNull(mc)) {
                builds.add(v);
                if (!mcOrder.contains(mc)) {
                    mcOrder.add(mc);
                }
            }
        }
        builds.sort(Comparator.comparing((String v) -> v, NeoForgeCatalog::compareVersion).reversed());
        mcOrder.sort(Comparator.comparing((String v) -> v, NeoForgeCatalog::compareVersion).reversed());
        for (String mc : mcOrder) {
            if (vanillaReleases.contains(mc)) {
                out.add(new Entry(mc, null, false));
            }
            int count = 0;
            for (String build : builds) {
                if (mc.equals(mcFor(build)) && !(build.equals(pinnedNeoForge) && mc.equals(pinnedMc))) {
                    if (count++ >= PER_MC_LIMIT) {
                        break;
                    }
                    out.add(new Entry(mc, build, false));
                }
            }
        }
        return out;
    }

    /** Case-insensitive match of every word of the query against "vanilla|neoforge mc build". */
    public static boolean matches(Entry entry, String query) {
        if (Objects.isNull(query) || query.isBlank()) {
            return true;
        }
        String hay = ((entry.isVanilla() ? "vanilla " : "neoforge " + entry.neoforge() + " ") + entry.mc())
                .toLowerCase(Locale.ROOT);
        for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!hay.contains(word)) {
                return false;
            }
        }
        return true;
    }
}
