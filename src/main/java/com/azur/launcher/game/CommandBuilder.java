package com.azur.launcher.game;

import com.google.gson.JsonElement;
import com.azur.launcher.model.Arguments;
import com.azur.launcher.model.VersionMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CommandBuilder {

    /** Network timeouts for HttpURLConnection callers without their own (mods fetching lists during construction). */
    static final List<String> NETWORK_TIMEOUT_ARGS = List.of(
            "-Dsun.net.client.defaultConnectTimeout=5000", "-Dsun.net.client.defaultReadTimeout=8000");

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
        command.addAll(NETWORK_TIMEOUT_ARGS);

        List<String> jvmArgs = expand(Objects.isNull(meta.getArguments()) ? null : meta.getArguments().getJvm(), placeholders);
        if (jvmArgs.isEmpty()) {
            jvmArgs.add("-Djava.library.path=" + profile.getNativesDir());
            jvmArgs.add("-cp");
            jvmArgs.add(profile.getClasspath());
        }
        command.addAll(jvmArgs);

        command.addAll(profile.getExtraJvmArgs());

        String mainClass = meta.getMainClass();
        if (Objects.isNull(mainClass) || mainClass.isBlank()) {
            throw new IllegalStateException("Version JSON has no mainClass");
        }
        command.add(mainClass);

        List<String> gameArgs = expand(Objects.isNull(meta.getArguments()) ? null : meta.getArguments().getGame(), placeholders);
        if (gameArgs.isEmpty() && Objects.nonNull(meta.getMinecraftArguments()) && !meta.getMinecraftArguments().isBlank()) {
            for (String token : splitPlain(meta.getMinecraftArguments())) {
                gameArgs.add(replacePlaceholders(token, placeholders));
            }
        }
        command.addAll(gameArgs);

        return command;
    }

    private static List<String> expand(List<Arguments.Entry> entries, Map<String, String> placeholders) {
        List<String> result = new ArrayList<>();
        if (Objects.isNull(entries)) {
            return result;
        }
        for (Arguments.Entry entry : entries) {
            if (!PlatformRules.isAllowed(entry.getRules())) {
                continue;
            }
            JsonElement value = entry.getValue();
            if (Objects.isNull(value) || value.isJsonNull()) {
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
        if (Objects.nonNull(value) && !value.isEmpty()) {
            list.add(value);
        }
    }

    public static String replacePlaceholders(String input, Map<String, String> placeholders) {
        if (Objects.isNull(input)) {
            return "";
        }
        String out = input;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            if (out.contains(entry.getKey())) {
                out = out.replace(entry.getKey(), Objects.isNull(entry.getValue()) ? "" : entry.getValue());
            }
        }
        return out;
    }

    public static List<String> splitPlain(String input) {
        List<String> tokens = new ArrayList<>();
        if (Objects.isNull(input) || input.isBlank()) {
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
