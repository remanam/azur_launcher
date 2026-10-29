package com.azur.launcher.game;

import com.google.gson.JsonElement;
import com.azur.launcher.model.Arguments;
import com.azur.launcher.model.VersionMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CommandBuilder {

    private CommandBuilder() {
    }

    public static List<String> build(LaunchProfile profile, VersionMeta meta) {
        Map<String, String> placeholders = profile.toPlaceholders();
        List<String> command = new ArrayList<>();

        command.add(profile.getJavaExecutable().toString());

        command.add("-Xmx" + profile.getMaxMemoryMb() + "m");
        command.add("-Xms" + profile.getMinMemoryMb() + "m");
        command.add("-Dminecraft.launcher.brand=" + LaunchProfile.LAUNCHER_NAME);
        command.add("-Dminecraft.launcher.version=" + LaunchProfile.LAUNCHER_VERSION);

        List<String> jvmArgs = expand(meta.getArguments() == null ? null : meta.getArguments().getJvm(), placeholders);
        if (jvmArgs.isEmpty()) {
            jvmArgs.add("-Djava.library.path=" + profile.getNativesDir());
            jvmArgs.add("-cp");
            jvmArgs.add(profile.getClasspath());
        }
        command.addAll(jvmArgs);

        command.addAll(profile.getExtraJvmArgs());

        String mainClass = meta.getMainClass();
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalStateException("Version JSON has no mainClass");
        }
        command.add(mainClass);

        List<String> gameArgs = expand(meta.getArguments() == null ? null : meta.getArguments().getGame(), placeholders);
        if (gameArgs.isEmpty() && meta.getMinecraftArguments() != null && !meta.getMinecraftArguments().isBlank()) {
            for (String token : splitPlain(meta.getMinecraftArguments())) {
                gameArgs.add(replacePlaceholders(token, placeholders));
            }
        }
        command.addAll(gameArgs);

        return command;
    }

    private static List<String> expand(List<Arguments.Entry> entries, Map<String, String> placeholders) {
        List<String> result = new ArrayList<>();
        if (entries == null) {
            return result;
        }
        for (Arguments.Entry entry : entries) {
            if (!PlatformRules.isAllowed(entry.getRules())) {
                continue;
            }
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (value.isJsonPrimitive()) {
                addIfNotBlank(result, replacePlaceholders(value.getAsString(), placeholders));
            } else if (value.isJsonArray()) {
                for (JsonElement element : value.getAsJsonArray()) {
                    if (element.isJsonPrimitive()) {
                        addIfNotBlank(result, replacePlaceholders(element.getAsString(), placeholders));
                    }
                }
            }
        }
        return result;
    }

    private static void addIfNotBlank(List<String> list, String value) {
        if (value != null && !value.isEmpty()) {
            list.add(value);
        }
    }

    public static String replacePlaceholders(String input, Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }
        String out = input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            if (out.contains(entry.getKey())) {
                out = out.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return out;
    }

    public static List<String> splitPlain(String input) {
        List<String> tokens = new ArrayList<>();
        if (input == null || input.isBlank()) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
