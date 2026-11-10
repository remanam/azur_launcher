package com.azur.launcher.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Path rules of the pack: only clean relative paths under the allowed roots, no symlinks, glob matching. */
public final class PackPaths {

    /** The only top-level folders of an instance the pack may write to. */
    public static final List<String> ALLOWED_ROOTS =
            List.of("mods", "config", "defaultconfigs", "resourcepacks", "shaderpacks");

    /** The only files at the instance root the pack may write (keep-if-exists). */
    public static final List<String> ROOT_FILES = List.of("servers.dat");

    private PackPaths() {
    }

    public static boolean validHash(String h) {
        return h != null && h.matches("[0-9a-f]{64}");
    }

    /** Zip-slip and traversal guard; mirrors the Go {@code SafeRel}. */
    public static boolean isSafeRel(String p) {
        if (p == null || p.isEmpty() || p.length() > 240 || p.startsWith("/") || p.contains("\\") || p.contains(":")
                || p.indexOf('\0') >= 0) {
            return false;
        }
        String[] parts = p.split("/", -1);
        for (String s : parts) {
            if (s.isEmpty() || s.equals(".") || s.equals("..") || s.endsWith(".") || s.endsWith(" ")) {
                return false;
            }
        }
        if (parts.length == 1) {
            return ROOT_FILES.contains(p);
        }
        return ALLOWED_ROOTS.contains(parts[0]);
    }

    /** Joins {@code base} and a safe relative path and refuses any existing symlink on the way. */
    public static Path safeJoin(Path base, String rel) throws PackException {
        if (!isSafeRel(rel)) {
            throw new PackException(PackException.APPLY, "unsafe path in the pack: " + rel);
        }
        Path cur = base;
        for (String s : rel.split("/")) {
            cur = cur.resolve(s);
            if (Files.isSymbolicLink(cur)) {
                throw new PackException(PackException.APPLY, "symbolic link on the way: " + rel);
            }
        }
        return cur;
    }

    /** Glob on the whole path, or on the base name when the pattern has no slash; {@code dir/**} = a subtree. */
    public static boolean match(String pattern, String rel) {
        String p = pattern.toLowerCase(Locale.ROOT);
        String r = rel.toLowerCase(Locale.ROOT);
        if (p.endsWith("/**")) {
            return r.startsWith(p.substring(0, p.length() - 2));
        }
        if (!p.contains("/")) {
            return globToRegex(p).matcher(r.substring(r.lastIndexOf('/') + 1)).matches();
        }
        return globToRegex(p).matcher(r).matches();
    }

    public static boolean matchAny(List<String> patterns, String rel) {
        for (String p : patterns) {
            if (match(p, rel)) {
                return true;
            }
        }
        return false;
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append("[^/]*");
                case '?' -> sb.append("[^/]");
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.toString());
    }
}
