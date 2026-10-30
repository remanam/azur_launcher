package com.azur.launcher.profile;

import com.azur.launcher.loader.NeoForgeCatalog;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads and validates {@code profile.json}; every problem is reported at once. */
public final class ProfileLoader {

    private static final Gson GSON = new Gson();
    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern MINECRAFT = Pattern.compile("1\\.\\d{1,2}(\\.\\d{1,2})?");
    private static final Pattern NEOFORGE = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,5}(-beta)?");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CHANNEL = Pattern.compile("[a-z0-9-]{1,32}");
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "-cp", "-classpath", "--class-path", "-Djava.library.path", "-javaagent", "-agentpath", "-agentlib",
            "-Xmx", "-Xms", "-jar", "-Dminecraft.launcher");
    private static final Set<Integer> JAVA_MAJORS = Set.of(17, 21, 25);

    private ProfileLoader() {
    }

    public static Profile load(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    }

    public static String toJson(Profile profile) {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(profile) + "\n";
    }

    public static Profile parse(String json, String origin) throws ProfileException {
        Profile profile;
        try {
            profile = GSON.fromJson(json, Profile.class);
        } catch (JsonParseException e) {
            throw new ProfileException("Cannot parse " + origin + ": " + e.getMessage(), e);
        }
        if (profile == null) {
            throw new ProfileException("Empty profile: " + origin);
        }
        List<String> problems = validate(profile);
        if (!problems.isEmpty()) {
            throw new ProfileException("Invalid profile " + origin + ": " + String.join("; ", problems));
        }
        return profile;
    }

    public static List<String> validate(Profile p) {
        List<String> bad = new ArrayList<>();
        if (p.schema() != Profile.SCHEMA) {
            bad.add("schema must be " + Profile.SCHEMA);
        }
        if (!matches(SERVER_ID, p.serverId())) {
            bad.add("serverId must match " + SERVER_ID);
        }
        if (!matches(MINECRAFT, p.minecraft())) {
            bad.add("minecraft must look like 1.21.1");
        }
        if (!matches(NEOFORGE, p.neoforge())) {
            bad.add("neoforge must look like 21.1.264");
        } else if (matches(MINECRAFT, p.minecraft())) {
            String prefix = NeoForgeCatalog.versionPrefix(p.minecraft());
            if (prefix == null || !p.neoforge().startsWith(prefix + ".")) {
                bad.add("neoforge " + p.neoforge() + " does not belong to Minecraft " + p.minecraft());
            }
        }
        if (!matches(SHA256, p.neoforgeInstallerSha256())) {
            bad.add("neoforgeInstallerSha256 must be 64 lowercase hex characters");
        }
        if (!JAVA_MAJORS.contains(p.java())) {
            bad.add("java must be one of " + JAVA_MAJORS);
        }
        if (p.ramMinMb() < 512 || p.ramMaxMb() < p.ramMinMb() || p.ramMaxMb() > 65536) {
            bad.add("ram must satisfy 512 <= ramMinMb <= ramMaxMb <= 65536");
        }
        if (!matches(CHANNEL, p.packChannel())) {
            bad.add("packChannel must match " + CHANNEL);
        }
        if (p.mirrorBase() != null && !p.mirrorBase().isBlank() && !isHttps(p.mirrorBase())) {
            bad.add("mirrorBase must be an https URL (or loopback http) or empty");
        }
        if (p.jvmArgs() == null) {
            bad.add("jvmArgs is required (may be empty)");
        } else {
            for (String arg : p.jvmArgs()) {
                if (arg == null || arg.isBlank() || !arg.startsWith("-") || arg.chars().anyMatch(Character::isISOControl)) {
                    bad.add("bad jvm argument: " + arg);
                } else if (FORBIDDEN_PREFIXES.stream().anyMatch(arg::startsWith)) {
                    bad.add("jvm argument is controlled by the launcher: " + arg);
                }
            }
        }
        return bad;
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static boolean isHttps(String url) {
        try {
            URI uri = URI.create(url.trim());
            return ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null)
                    || com.azur.launcher.net.HostPolicy.isLoopbackHttp(url.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
