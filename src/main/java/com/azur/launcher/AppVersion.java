package com.azur.launcher;

/**
 * Versions compiled into this jar. {@code APP} is the launcher version (bump with every release, see
 * docs/plans/LAUNCHER_SELF_UPDATE.md). {@code WRAPPER} is the version of the bootstrap + jpackage wrapper; change it
 * only when {@link Launcher} or the packaging changes in a way that needs a new installer (then also raise
 * {@code min_wrapper} in launcher.json). The running wrapper's value is the one of the EMBEDDED jar, see
 * {@link Launcher#WRAPPER_PROPERTY}.
 */
public final class AppVersion {

    public static final String APP = load();
    public static final String WRAPPER = "1.0.0";

    private AppVersion() {
    }

    /**
     * Read from {@code azur-version.properties} of the jar this class comes from (filled from build.gradle), so a jar
     * run from {@code updates/current.jar} reports its own version, not the embedded one. A literal here once stayed
     * at 1.0.7 for three releases: the launcher kept offering an update it already ran and never confirmed the start.
     */
    private static String load() {
        try (java.io.InputStream in = AppVersion.class.getResourceAsStream("/azur-version.properties")) {
            if (in != null) {
                java.util.Properties p = new java.util.Properties();
                p.load(in);
                String v = p.getProperty("version", "").trim();
                if (v.matches("\\d+(\\.\\d+)*")) {
                    return v;
                }
            }
        } catch (java.io.IOException ignored) {
            // falls through to the unknown version
        }
        return "0";
    }
}
