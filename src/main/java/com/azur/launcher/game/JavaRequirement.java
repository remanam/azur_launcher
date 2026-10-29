package com.azur.launcher.game;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Which Java major version a game version needs. */
public final class JavaRequirement {

    public static final int DEFAULT_MAJOR = 21;
    private static final int MAX_INHERIT_DEPTH = 5;

    private JavaRequirement() {
    }

    /**
     * Major version from the version JSON ({@code javaVersion.majorVersion}, following {@code inheritsFrom}),
     * or the rule for the release number when the JSON declares nothing.
     */
    public static int requiredMajor(String versionId, Path versionJson) {
        if (versionJson != null) {
            int declared = readDeclared(versionJson, 0);
            if (declared > 0) {
                return declared;
            }
        }
        return ruleMajorFor(versionId);
    }

    private static int readDeclared(Path versionJson, int depth) {
        if (depth > MAX_INHERIT_DEPTH || versionJson == null || !Files.isRegularFile(versionJson)) {
            return -1;
        }
        JsonObject root;
        try {
            root = new Gson().fromJson(Files.readString(versionJson, StandardCharsets.UTF_8), JsonObject.class);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        if (root == null) {
            return -1;
        }
        if (root.has("javaVersion") && root.get("javaVersion").isJsonObject()) {
            JsonObject javaVersion = root.getAsJsonObject("javaVersion");
            if (javaVersion.has("majorVersion") && !javaVersion.get("majorVersion").isJsonNull()) {
                try {
                    int major = javaVersion.get("majorVersion").getAsInt();
                    if (major > 0) {
                        return major;
                    }
                } catch (RuntimeException ignored) {
                    // fall through to inheritsFrom
                }
            }
        }
        if (root.has("inheritsFrom") && !root.get("inheritsFrom").isJsonNull() && versionJson.getParent() != null
                && versionJson.getParent().getParent() != null) {
            String parent = root.get("inheritsFrom").getAsString();
            if (parent != null && !parent.isBlank()) {
                Path parentJson = versionJson.getParent().getParent().resolve(parent).resolve(parent + ".json");
                return readDeclared(parentJson, depth + 1);
            }
        }
        return -1;
    }

    /** Release-number rule: up to 1.16 Java 8, up to 1.19 Java 17, 1.20.5 and later Java 21. */
    public static int ruleMajorFor(String versionId) {
        int[] parsed = parseReleaseNumber(versionId);
        if (parsed == null) {
            return DEFAULT_MAJOR;
        }
        int minor = parsed[0];
        int patch = parsed[1];
        if (minor <= 16) {
            return 8;
        }
        if (minor <= 19) {
            return 17;
        }
        if (minor == 20) {
            return patch >= 5 ? 21 : 17;
        }
        return 21;
    }

    private static int[] parseReleaseNumber(String versionId) {
        if (versionId == null) {
            return null;
        }
        String text = versionId.trim().toLowerCase(Locale.ROOT);
        if (!text.startsWith("1.")) {
            return null;
        }
        int index = 2;
        int minorStart = index;
        while (index < text.length() && Character.isDigit(text.charAt(index))) {
            index++;
        }
        if (index == minorStart) {
            return null;
        }
        int minor = Integer.parseInt(text.substring(minorStart, index));
        int patch = 0;
        if (index < text.length() && text.charAt(index) == '.') {
            int patchStart = ++index;
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (index > patchStart) {
                patch = Integer.parseInt(text.substring(patchStart, index));
            }
        }
        return new int[]{minor, patch};
    }

    /** Download page of a Temurin build for the current OS and CPU (opened in the browser). */
    public static String downloadUrl(int major) {
        String os = JavaDetector.isWindows() ? "windows" : JavaDetector.isMac() ? "mac" : "linux";
        String arch = PlatformRules.OS_ARCH.equals("arm64") ? "aarch64" : "x64";
        return "https://adoptium.net/temurin/releases/?os=" + os + "&arch=" + arch + "&package=jdk&version=" + major;
    }
}
