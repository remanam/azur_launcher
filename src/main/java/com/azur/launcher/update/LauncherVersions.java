package com.azur.launcher.update;

/** Dotted numeric versions ("1.2.10"); a non-numeric part counts as 0, a missing part as 0. */
public final class LauncherVersions {

    private LauncherVersions() {
    }

    public static int compare(String a, String b) {
        String[] x = a.trim().split("\\.");
        String[] y = b.trim().split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int cmp = Long.compare(part(x, i), part(y, i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    public static boolean newer(String candidate, String than) {
        return compare(candidate, than) > 0;
    }

    private static long part(String[] parts, int i) {
        if (i >= parts.length) {
            return 0;
        }
        String digits = parts[i].replaceAll("[^0-9].*$", "");
        try {
            return digits.isEmpty() ? 0 : Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
