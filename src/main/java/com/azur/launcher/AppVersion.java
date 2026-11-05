package com.azur.launcher;

/**
 * Versions compiled into this jar. {@code APP} is the launcher version (bump with every release, see
 * docs/plans/LAUNCHER_SELF_UPDATE.md). {@code WRAPPER} is the version of the bootstrap + jpackage wrapper; change it
 * only when {@link Launcher} or the packaging changes in a way that needs a new installer (then also raise
 * {@code min_wrapper} in launcher.json). The running wrapper's value is the one of the EMBEDDED jar, see
 * {@link Launcher#WRAPPER_PROPERTY}.
 */
public final class AppVersion {

    public static final String APP = "1.0.7";
    public static final String WRAPPER = "1.0.0";

    private AppVersion() {
    }
}
