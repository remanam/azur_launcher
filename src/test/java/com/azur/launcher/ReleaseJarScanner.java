package com.azur.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Looks for "offline" (any case) in entry names and in the decompressed bytes of every entry of a jar.
 * Whitelist: the OAuth scope {@code offline_access} (the refresh-token permission of Microsoft sign-in, not
 * an offline account). Anything else is a hit.
 */
public final class ReleaseJarScanner {

    static final List<String> WHITELIST = List.of("offline_access");

    private ReleaseJarScanner() {
    }

    public static List<String> findHits(Path jar) throws IOException {
        List<String> hits = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(file.entries())) {
                if (containsForbidden(entry.getName())) {
                    hits.add("entry name: " + entry.getName());
                }
                if (!entry.isDirectory()) {
                    try (var in = file.getInputStream(entry)) {
                        // ISO-8859-1 maps bytes 1:1, so ASCII text inside class constant pools is found
                        String content = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
                        if (containsForbidden(content)) {
                            hits.add("content: " + entry.getName());
                        }
                    }
                }
            }
        }
        return hits;
    }

    static boolean containsForbidden(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String allowed : WHITELIST) {
            lower = lower.replace(allowed, "");
        }
        return lower.contains("offline");
    }
}
