package com.azur.launcher.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Parses a JVM thread dump and names the mod the game start is stuck in. */
public record HangDiagnosis(String thread, String mod, boolean waitingNetwork, List<String> frames) {

    private static final List<String> SYSTEM = List.of("java.", "javax.", "jdk.", "sun.", "net.neoforged.", "cpw.",
            "com.mojang.", "net.minecraft.", "org.spongepowered.", "com.google.", "it.unimi.", "org.apache.", "io.netty.");
    private static final List<String> NET = List.of("Net.connect0", "SocketInputStream", "SSLSocketImpl",
            "HttpURLConnection", "NioSocketImpl", "SocketDispatcher");
    private static final List<String> THREADS = List.of("modloading-worker", "Render thread", "main");

    /** Latest full dump in the output, from its "Full thread dump" header; empty if none. */
    public static List<String> lastDump(List<String> lines) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("Full thread dump")) {
                start = i;
            }
        }
        return start < 0 ? List.of() : new ArrayList<>(lines.subList(start, lines.size()));
    }

    /** Picks the first suspicious thread in RUNNABLE/BLOCKED state; null if the dump has none. */
    public static HangDiagnosis parse(List<String> dump) {
        for (String wanted : THREADS) {
            HangDiagnosis d = forThread(dump, wanted);
            if (Objects.nonNull(d)) {
                return d;
            }
        }
        return null;
    }

    private static HangDiagnosis forThread(List<String> dump, String prefix) {
        for (int i = 0; i < dump.size(); i++) {
            String head = dump.get(i);
            if (!head.startsWith("\"" + prefix)) {
                continue;
            }
            List<String> frames = new ArrayList<>();
            boolean active = false;
            for (int j = i + 1; j < dump.size() && !dump.get(j).isBlank(); j++) {
                String line = dump.get(j).trim();
                if (line.startsWith("java.lang.Thread.State:")) {
                    active = line.contains("RUNNABLE") || line.contains("BLOCKED");
                } else if (line.startsWith("at ")) {
                    frames.add(line.substring(3));
                }
            }
            if (!active) {
                continue;
            }
            String thread = head.substring(1, head.indexOf('"', 1));
            boolean net = frames.stream().anyMatch(f -> NET.stream().anyMatch(f::contains));
            return new HangDiagnosis(thread, modOf(frames), net, frames);
        }
        return null;
    }

    private static String modOf(List<String> frames) {
        for (String frame : frames) {
            if (SYSTEM.stream().noneMatch(frame::startsWith)) {
                return packageToMod(frame);
            }
        }
        return null;
    }

    /** {@code org.zeith.hammerlib.core.X.m} gives "hammerlib": the segment after the vendor prefix. */
    static String packageToMod(String frame) {
        String[] p = frame.split("\\.");
        if (p.length >= 3 && List.of("org", "com", "net", "io", "dev", "me").contains(p[0])) {
            return p[2].toLowerCase(Locale.ROOT);
        }
        return p[0].toLowerCase(Locale.ROOT);
    }

    /** Short user text, language "ru" or anything else for English. */
    public String message(String lang) {
        boolean ru = "ru".equals(lang);
        String who = Objects.isNull(mod) ? (ru ? "не определён" : "unknown") : mod;
        String wait = waitingNetwork ? (ru ? " (ждёт ответа сети)" : " (waiting for a network reply)") : "";
        String hint = ru ? "Проверьте интернет/файрвол или отключите мод."
                : "Check your internet/firewall or disable the mod.";
        return (ru ? "Игра зависла на загрузке модов. Подозреваемый мод: " : "The game hung while loading mods. Suspect mod: ")
                + who + wait + ". " + hint + " (" + (ru ? "поток" : "thread") + ": " + thread + ")";
    }
}
