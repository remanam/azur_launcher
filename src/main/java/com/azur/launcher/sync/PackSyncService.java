package com.azur.launcher.sync;

import com.azur.launcher.config.AppDirs;
import com.azur.launcher.net.HostPolicy;
import com.azur.launcher.net.HttpUtils;
import com.azur.launcher.pack.PackClient;
import com.azur.launcher.pack.PackException;
import com.azur.launcher.pack.PackServer;
import com.azur.launcher.pack.PackSource;
import com.azur.launcher.pack.PackState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Pack sync for the UI on top of the in-process {@link PackClient} (no external updater program). Result codes are the
 * {@link PackException} codes ({@code nopack}, {@code signature}, {@code network}, {@code outdated}, {@code apply},
 * {@code verify}) plus {@code unknown}; {@code ""} means success. The pack server host must be allowed by the launcher's
 * {@link HostPolicy} (the servers list adds its host). Blocking: call from a background thread.
 *
 * <p>Maintenance: the host's banner of the last {@link #sync} is kept in {@link #maintenanceBanner()}; an update still
 * runs (the documented flow is to update while the game server is stopped).
 */
public final class PackSyncService {

    /** Result of a run. {@code code} is empty on success. */
    public record Result(int exit, String code, String message, String packVersion) {
        public boolean ok() {
            return exit == 0;
        }
    }

    /** What the pack server reports without a manifest download; {@code version} is empty when unreachable. */
    public record Remote(boolean reachable, String version, String banner) {
        static final Remote UNREACHABLE = new Remote(false, "", "");
    }

    private final Path home;
    private final Supplier<HostPolicy> policy;
    private final boolean upgradeHttp;
    private volatile String banner = "";

    /** @param home the client's own state folder; @param policy evaluated per run (the allowed set may grow) */
    public PackSyncService(Path home, Supplier<HostPolicy> policy, boolean upgradeHttp) {
        this.home = home;
        this.policy = policy;
        this.upgradeHttp = upgradeHttp;
    }

    /** The launcher's service: state under {@code <data>/updater}, host policy of {@link HttpUtils}. */
    public static PackSyncService standard(AppDirs dirs) {
        return new PackSyncService(dirs.updaterHome(), () -> HttpUtils.downloader().policy(), true);
    }

    /** Pack version this launcher installed into {@code instance} (local file read), or empty. */
    public String installedVersion(Path instance) {
        return PackState.load(home, instance).packVersion;
    }

    /** Cheap {@code /status} request (no manifest, no writes): newest pack version and the maintenance banner. */
    public Remote probe(PackSource source) {
        return probe(source, java.time.Duration.ofSeconds(15));
    }

    /** Same, with a custom timeout (Play uses a short one and never a cache). */
    public Remote probe(PackSource source, java.time.Duration timeout) {
        try {
            PackServer.Status status = new PackServer(secure(source), policy.get()).status(timeout);
            String text = status.maintenance() ? nz(status.message()) : "";
            banner = text;
            return new Remote(true, nz(status.packVersion()), text);
        } catch (PackException | RuntimeException e) {
            return Remote.UNREACHABLE;
        }
    }

    private PackSource secure(PackSource source) {
        return upgradeHttp && !source.url().isEmpty() ? source.withUrl(HttpUtils.secure(source.url())) : source;
    }

    /** The host's maintenance text from the last sync, or empty. */
    public Optional<String> maintenanceBanner() {
        return banner.isEmpty() ? Optional.empty() : Optional.of(banner);
    }

    /** Brings {@code instance} to the pack of {@code source}. */
    public Result sync(PackSource source, Path instance, SyncProgress progress) {
        try {
            PackClient client = new PackClient(home, policy.get());
            PackClient.Checked checked = client.check(secure(source), instance);
            banner = checked.status().maintenance() ? nz(checked.status().message()) : "";
            String version = client.update(checked, instance, progress::onProgress);
            return new Result(0, "", "", version);
        } catch (PackException e) {
            return new Result(1, e.code(), e.getMessage(), "");
        } catch (IOException | RuntimeException e) {
            return new Result(1, "unknown", String.valueOf(e.getMessage()), "");
        }
    }

    /** Re-hashes the installed pack files without network access. */
    public Result verify(Path instance) {
        try {
            return new Result(0, "", "", new PackClient(home, policy.get()).verify(instance));
        } catch (PackException e) {
            return new Result(1, e.code(), e.getMessage(), "");
        } catch (IOException | RuntimeException e) {
            return new Result(1, "unknown", String.valueOf(e.getMessage()), "");
        }
    }

    /** Restores the newest backup of the last update. */
    public Result rollback(Path instance) {
        try {
            return new Result(0, "", "", new PackClient(home, policy.get()).rollback(instance));
        } catch (PackException e) {
            return new Result(1, e.code(), e.getMessage(), "");
        } catch (IOException | RuntimeException e) {
            return new Result(1, "unknown", String.valueOf(e.getMessage()), "");
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
