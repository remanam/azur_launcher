package com.azur.launcher.config;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * The only place that decides where launcher data lives.
 *
 * <ul>
 *   <li>Windows: {@code %APPDATA%\AzurLauncher}</li>
 *   <li>macOS: {@code ~/Library/Application Support/AzurLauncher}</li>
 *   <li>Linux: {@code $XDG_DATA_HOME/azur-launcher}, default {@code ~/.local/share/azur-launcher}</li>
 * </ul>
 * The system property {@code azur.home} overrides the root (tests, portable use).
 */
public final class AppDirs {

    public static final String HOME_PROPERTY = "azur.home";

    private final Path root;

    private AppDirs(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static AppDirs at(Path root) {
        return new AppDirs(root);
    }

    public static AppDirs system() {
        String override = System.getProperty(HOME_PROPERTY, "").trim();
        if (!override.isEmpty()) {
            return new AppDirs(Path.of(override));
        }
        Path userHome = Path.of(System.getProperty("user.home", "."));
        return new AppDirs(resolveRoot(System.getProperty("os.name", ""), userHome, System.getenv()));
    }

    /** Platform rule, separated from the environment so it can be tested. */
    public static Path resolveRoot(String osName, Path userHome, Map<String, String> env) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String appData = env.get("APPDATA");
            Path base = appData == null || appData.isBlank()
                    ? userHome.resolve("AppData").resolve("Roaming") : Path.of(appData);
            return base.resolve("AzurLauncher");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return userHome.resolve("Library").resolve("Application Support").resolve("AzurLauncher");
        }
        String xdg = env.get("XDG_DATA_HOME");
        Path base = xdg == null || xdg.isBlank() ? userHome.resolve(".local").resolve("share") : Path.of(xdg);
        return base.resolve("azur-launcher");
    }

    public Path root() {
        return root;
    }

    public Path configFile() {
        return root.resolve("config.json");
    }

    public Path accountsFile() {
        return root.resolve("accounts").resolve("accounts.json");
    }

    /** Default game directory (versions, libraries, assets). */
    public Path gameDir() {
        return root.resolve("game");
    }

    /** Shared download cache (installers); safe to delete. */
    public Path cacheDir() {
        return root.resolve("cache");
    }

    /** One folder per server instance: pinned profile, installed state, game directory. */
    public Path instancesDir() {
        return root.resolve("instances");
    }

    public Path logsDir() {
        return root.resolve("logs");
    }

    /** Server list and pack sync settings. */
    public Path packSettingsFile() {
        return root.resolve("pack.json");
    }

    /** Encrypted invite code of the pack server. */
    public Path inviteVaultFile() {
        return root.resolve("accounts").resolve("invite.vault");
    }

    /** Last verified servers.json. */
    public Path serversCacheFile() {
        return root.resolve("cache").resolve("servers.json");
    }

    /** Optional override of the embedded servers.json public key (a file with the base64 key). */
    public Path serversKeyFile() {
        return root.resolve("servers_pubkey.txt");
    }

    /** Where the azur-updater executable is looked for or extracted to. */
    public Path binDir() {
        return root.resolve("bin");
    }

    /** The updater's own state (backups, journals); separate from the launcher config. */
    public Path updaterHome() {
        return root.resolve("updater");
    }
}
