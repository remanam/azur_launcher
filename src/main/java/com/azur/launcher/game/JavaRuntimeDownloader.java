package com.azur.launcher.game;

import com.azur.launcher.config.AppDirs;
import com.azur.launcher.net.HttpUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Last resort when no Java 21 is installed: downloads Mojang's own JRE ({@code java-runtime-delta}) into
 * {@code <app>/cache/runtime/<component>/} with SHA-1 checks. A finished download is marked by {@code .complete}
 * and reused.
 */
public final class JavaRuntimeDownloader {

    static final String ALL_URL =
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";
    private static final String MARKER = ".complete";

    private final AppDirs dirs;
    private final Consumer<String> log;

    public JavaRuntimeDownloader(AppDirs dirs, Consumer<String> log) {
        this.dirs = dirs;
        this.log = log;
    }

    /** Mojang platform key for this machine, e.g. {@code mac-os-arm64}; empty when unsupported. */
    static Optional<String> platformKey(String os, String arch) {
        String o = os.toLowerCase(Locale.ROOT);
        String a = arch.toLowerCase(Locale.ROOT);
        boolean arm = a.contains("aarch64") || a.contains("arm64");
        boolean x64 = a.equals("amd64") || a.equals("x86_64");
        if (o.contains("mac")) {
            return arm ? Optional.of("mac-os-arm64") : x64 ? Optional.of("mac-os") : Optional.empty();
        }
        if (o.contains("win")) {
            return arm ? Optional.of("windows-arm64") : x64 ? Optional.of("windows-x64") : Optional.empty();
        }
        return x64 ? Optional.of("linux") : Optional.empty();
    }

    /** Component name ({@code java-runtime-delta}) of the first entry for the major version, or empty. */
    static Optional<String> componentFor(JsonObject platform, int major) {
        for (Map.Entry<String, JsonElement> e : platform.entrySet()) {
            if (!e.getValue().isJsonArray() || e.getValue().getAsJsonArray().isEmpty()) {
                continue;
            }
            JsonObject entry = e.getValue().getAsJsonArray().get(0).getAsJsonObject();
            JsonObject version = entry.has("version") ? entry.getAsJsonObject("version") : null;
            if (Objects.nonNull(version) && version.has("name")
                    && JavaDetector.parseMajorVersion(version.get("name").getAsString()) == major) {
                return Optional.of(e.getKey());
            }
        }
        return Optional.empty();
    }

    /** Returns the {@code java} executable of the cached or freshly downloaded JRE. */
    public Path ensure(int major) throws IOException {
        String key = platformKey(System.getProperty("os.name", ""), System.getProperty("os.arch", ""))
                .orElseThrow(() -> new IOException("No Mojang Java runtime for this platform"));
        JsonObject platform = JsonParser.parseString(HttpUtils.getString(ALL_URL)).getAsJsonObject()
                .getAsJsonObject(key);
        String component = componentFor(platform, major)
                .orElseThrow(() -> new IOException("Mojang has no Java " + major + " runtime for " + key));
        Path root = dirs.cacheDir().resolve("runtime").resolve(component);
        Path home = JavaDetector.isMac() ? root.resolve("jre.bundle/Contents/Home") : root;
        Path exe = home.resolve("bin").resolve(JavaDetector.isWindows() ? "java.exe" : "java");
        if (Files.isRegularFile(root.resolve(MARKER)) && Files.isRegularFile(exe)) {
            return exe;
        }
        JsonObject manifestRef = platform.getAsJsonArray(component).get(0).getAsJsonObject()
                .getAsJsonObject("manifest");
        JsonObject files = JsonParser.parseString(HttpUtils.getString(manifestRef.get("url").getAsString()))
                .getAsJsonObject().getAsJsonObject("files");
        log.accept("Downloading Java " + major + " (" + component + "), " + files.size() + " entries");
        for (Map.Entry<String, JsonElement> e : files.entrySet()) {
            install(root, e.getKey(), e.getValue().getAsJsonObject());
        }
        Files.writeString(root.resolve(MARKER), component);
        if (!Files.isRegularFile(exe)) {
            throw new IOException("Downloaded Java runtime has no " + exe);
        }
        return exe;
    }

    private static void install(Path root, String rel, JsonObject entry) throws IOException {
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Unsafe runtime path: " + rel);
        }
        switch (entry.get("type").getAsString()) {
            case "directory" -> Files.createDirectories(target);
            case "link" -> {
                Files.createDirectories(target.getParent());
                Files.deleteIfExists(target);
                Files.createSymbolicLink(target, Path.of(entry.get("target").getAsString()));
            }
            default -> {
                JsonObject raw = entry.getAsJsonObject("downloads").getAsJsonObject("raw");
                Files.createDirectories(target.getParent());
                HttpUtils.download(raw.get("url").getAsString(), target, raw.get("sha1").getAsString(),
                        raw.get("size").getAsLong());
                if (entry.has("executable") && entry.get("executable").getAsBoolean()) {
                    target.toFile().setExecutable(true);
                }
            }
        }
    }
}
