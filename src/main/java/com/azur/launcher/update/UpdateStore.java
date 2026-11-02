package com.azur.launcher.update;

import com.azur.launcher.net.Hashes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The {@code <data>/updates/} folder and the rules for which jar runs. Plain files, no JSON (the bootstrap uses it):
 * <pre>
 * pending.jar + pending.properties   downloaded, verified, not applied yet
 * current.jar + current.properties   the applied update (version, sha256)
 * current.jar.prev + current.properties.prev   the jar it replaced
 * attempt.properties                 version + start time of the first run of a freshly applied jar
 * ok.properties                      version that confirmed a successful start
 * rejected.txt                       versions that failed to start (never downloaded again)
 * </pre>
 * Rollback: an applied jar has {@link #graceMillis()} to write {@code ok.properties} ({@link #confirmStarted}); if the
 * next start finds an unconfirmed attempt older than that, the jar is rejected and the previous one restored.
 */
public final class UpdateStore {

    public static final long DEFAULT_GRACE_MILLIS = 90_000;

    private final Path dir;
    private final LongSupplier clock;
    private final long graceMillis;

    UpdateStore(Path dir, LongSupplier clock, long graceMillis) {
        this.dir = dir;
        this.clock = clock;
        this.graceMillis = graceMillis;
    }

    public static UpdateStore at(Path dir) {
        return new UpdateStore(dir, System::currentTimeMillis, DEFAULT_GRACE_MILLIS);
    }

    static UpdateStore at(Path dir, LongSupplier clock, long graceMillis) {
        return new UpdateStore(dir, clock, graceMillis);
    }

    long graceMillis() {
        return graceMillis;
    }

    public Path dir() {
        return dir;
    }

    Path currentJar() {
        return dir.resolve("current.jar");
    }

    Path pendingJar() {
        return dir.resolve("pending.jar");
    }

    /**
     * Decides which jar to run: settles an unconfirmed attempt (rollback), applies a pending update, then returns
     * {@code current.jar} if it is intact and newer than {@code embeddedVersion}. Empty = run the embedded jar.
     */
    public Optional<Path> select(String embeddedVersion) {
        try {
            settleAttempt();
            applyPending(embeddedVersion);
            return intactCurrent(embeddedVersion);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The app started: confirms the version so the next start keeps it. */
    public void confirmStarted(String runningVersion) {
        Properties attempt = read("attempt.properties");
        if (attempt != null && runningVersion.equals(attempt.getProperty("version"))) {
            write("ok.properties", props("version", runningVersion));
            delete("attempt.properties");
        }
    }

    /** The updated jar could not even be loaded: reject it at once and restore the previous one. */
    public void rollbackNow() {
        Properties attempt = read("attempt.properties");
        Properties current = read("current.properties");
        String version = attempt != null ? attempt.getProperty("version")
                : current != null ? current.getProperty("version") : null;
        rollback(version);
    }

    /** Version of the verified pending update, or null. */
    public String pendingVersion() {
        Properties pending = read("pending.properties");
        return pending != null && Files.isRegularFile(pendingJar()) ? pending.getProperty("version") : null;
    }

    public boolean isRejected(String version) {
        return rejected().contains(version);
    }

    /** Moves a downloaded and already verified jar into place as the pending update. */
    public void stagePending(Path verifiedJar, String version, String sha256) throws IOException {
        Files.createDirectories(dir);
        delete("pending.properties");
        Files.move(verifiedJar, pendingJar(), StandardCopyOption.REPLACE_EXISTING);
        write("pending.properties", props("version", version, "sha256", sha256));
    }

    private void settleAttempt() throws IOException {
        Properties attempt = read("attempt.properties");
        if (attempt == null) {
            return;
        }
        Properties ok = read("ok.properties");
        String version = attempt.getProperty("version");
        if (ok != null && version.equals(ok.getProperty("version"))) {
            delete("attempt.properties");
            return;
        }
        long started = Long.parseLong(attempt.getProperty("startedAt", "0"));
        if (clock.getAsLong() - started >= graceMillis) {
            rollback(version);
        }
    }

    private void applyPending(String embeddedVersion) throws IOException {
        Properties pending = read("pending.properties");
        if (pending == null || !Files.isRegularFile(pendingJar())) {
            return;
        }
        String version = pending.getProperty("version");
        String sha = pending.getProperty("sha256", "");
        Properties current = read("current.properties");
        String floor = current == null ? embeddedVersion : newest(embeddedVersion, current.getProperty("version"));
        boolean usable = LauncherVersions.newer(version, floor) && !isRejected(version)
                && sha.equals(Hashes.sha256(pendingJar()));
        if (usable) {
            if (Files.isRegularFile(currentJar()) && current != null) {
                Files.move(currentJar(), dir.resolve("current.jar.prev"), StandardCopyOption.REPLACE_EXISTING);
                Files.move(dir.resolve("current.properties"), dir.resolve("current.properties.prev"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(pendingJar(), currentJar(), StandardCopyOption.REPLACE_EXISTING);
            write("current.properties", props("version", version, "sha256", sha));
            write("attempt.properties", props("version", version, "startedAt", Long.toString(clock.getAsLong())));
            delete("ok.properties");
        } else {
            Files.deleteIfExists(pendingJar());
        }
        delete("pending.properties");
    }

    private Optional<Path> intactCurrent(String embeddedVersion) throws IOException {
        Properties current = read("current.properties");
        if (current == null || !Files.isRegularFile(currentJar())) {
            return Optional.empty();
        }
        boolean newer = LauncherVersions.newer(current.getProperty("version", "0"), embeddedVersion);
        if (newer && current.getProperty("sha256", "").equals(Hashes.sha256(currentJar()))) {
            return Optional.of(currentJar());
        }
        // Older than the embedded jar (a new installer was installed) or damaged: drop it.
        Files.deleteIfExists(currentJar());
        delete("current.properties");
        return Optional.empty();
    }

    private void rollback(String badVersion) {
        if (badVersion != null) {
            Set<String> all = rejected();
            all.add(badVersion);
            try {
                Files.writeString(dir.resolve("rejected.txt"), String.join("\n", all) + "\n");
            } catch (IOException ignored) {
                // a lost rejection only means one more attempt later
            }
        }
        try {
            Files.deleteIfExists(currentJar());
            delete("current.properties");
            Path prev = dir.resolve("current.jar.prev");
            if (Files.isRegularFile(prev) && Files.isRegularFile(dir.resolve("current.properties.prev"))) {
                Files.move(prev, currentJar(), StandardCopyOption.REPLACE_EXISTING);
                Files.move(dir.resolve("current.properties.prev"), dir.resolve("current.properties"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        delete("attempt.properties");
        delete("ok.properties");
    }

    private Set<String> rejected() {
        Set<String> all = new LinkedHashSet<>();
        try {
            Path file = dir.resolve("rejected.txt");
            if (Files.isRegularFile(file)) {
                Files.readAllLines(file).stream().map(String::trim).filter(s -> !s.isEmpty()).forEach(all::add);
            }
        } catch (IOException ignored) {
            // unreadable list = nothing rejected
        }
        return all;
    }

    private static String newest(String a, String b) {
        return LauncherVersions.newer(b == null ? "0" : b, a) ? b : a;
    }

    private Properties read(String name) {
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version") == null ? null : p;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private void write(String name, Properties p) {
        try {
            Files.createDirectories(dir);
            Path part = dir.resolve(name + ".part");
            try (OutputStream out = Files.newOutputStream(part)) {
                p.store(out, null);
            }
            Files.move(part, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void delete(String name) {
        try {
            Files.deleteIfExists(dir.resolve(name));
        } catch (IOException ignored) {
            // leftover state files are re-evaluated on the next start
        }
    }

    private static Properties props(String... keyValues) {
        Properties p = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            p.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return p;
    }
}
