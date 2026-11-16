package com.azur.launcher.game;

import com.azur.launcher.model.Rule;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class PlatformRules {

    public static final String OS_NAME = detectOsName();
    public static final String OS_ARCH = detectOsArch();
    public static final String OS_VERSION = System.getProperty("os.version", "");

    private static volatile Map<String, Boolean> features = Map.of();

    private PlatformRules() {
    }

    public static void setFeatures(Map<String, Boolean> features) {
        PlatformRules.features = Objects.isNull(features) ? Map.of() : features;
    }

    public static void setFeature(String name, boolean enabled) {
        java.util.HashMap<String, Boolean> copy = new java.util.HashMap<>(features);
        copy.put(name, enabled);
        features = copy;
    }

    public static boolean isFeatureEnabled(String name) {
        return Boolean.TRUE.equals(features.get(name));
    }

    /** windows / osx / linux */
    public static String detectOsName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "osx";
        }
        return "linux";
    }

    /** x86_64 / x86 / arm64 / arm32 */
    public static String detectOsArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        if (arch.contains("arm")) {
            return "arm32";
        }
        if (arch.contains("64")) {
            return "x86_64";
        }
        return "x86";
    }

    public static boolean isAllowed(List<Rule> rules) {
        if (Objects.isNull(rules) || rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (Rule rule : rules) {
            if (matches(rule)) {
                allowed = "allow".equalsIgnoreCase(rule.getAction());
            }
        }
        return allowed;
    }

    private static boolean matches(Rule rule) {
        Rule.Os os = rule.getOs();
        if (Objects.nonNull(os)) {
            if (Objects.nonNull(os.getName()) && !os.getName().isEmpty() && !os.getName().equalsIgnoreCase(OS_NAME)) {
                return false;
            }
            if (Objects.nonNull(os.getArch()) && !os.getArch().isEmpty() && !os.getArch().equalsIgnoreCase(OS_ARCH)) {
                return false;
            }
            if (Objects.nonNull(os.getVersion()) && !os.getVersion().isEmpty()) {
                try {
                    if (!Pattern.compile(os.getVersion()).matcher(OS_VERSION).find()) {
                        return false;
                    }
                } catch (Exception ignored) {
                    return false;
                }
            }
        }
        List<String> required = rule.getFeatures();
        if (Objects.nonNull(required)) {
            for (String feature : required) {
                if (!isFeatureEnabled(feature)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static String nativesClassifier(Map<String, String> natives) {
        if (Objects.isNull(natives) || natives.isEmpty()) {
            return null;
        }
        String classifier = natives.get(OS_NAME);
        if (Objects.isNull(classifier)) {
            return null;
        }
        if (classifier.contains("${arch}")) {
            String arch = "x86_64".equals(OS_ARCH) ? "64" : "32";
            classifier = classifier.replace("${arch}", arch);
        }
        return classifier;
    }
}
