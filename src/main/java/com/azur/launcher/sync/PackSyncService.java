package com.azur.launcher.sync;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code azur-updater sync|verify} as a separate process and turns its JSON lines into progress and a result.
 * The command is an argument list (no shell). Blocking: call from a background thread.
 *
 * <p>A binary that is older than the machine mode ({@code sync}/{@code verify}) does not answer with JSON at all: it
 * ignores the arguments, starts its local web UI (the {@code Azur Updater: http://127.0.0.1:...} banner) and blocks
 * until Ctrl+C. That is reported as the {@code stale} error code, and the process is killed, so the launcher can never
 * hang on it (see {@code build.gradle}: a jar with updater binaries older than the Go sources is not built).
 */
public final class PackSyncService {

    /** Result of a run. {@code code} is the updater's error code ({@code ""} on success, see the README). */
    public record Result(int exit, String code, String message, String packVersion) {
        public boolean ok() {
            return exit == 0;
        }
    }

    /** Starts the process (replaceable in tests). Output must merge stdout and stderr. */
    @FunctionalInterface
    public interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    /** How long the machine mode may take to print its first JSON line before the binary is considered wrong. */
    static final Duration FIRST_EVENT_TIMEOUT = Duration.ofSeconds(30);
    /** A silence in the middle of a run: downloads may pause, so the limit is generous. */
    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);
    /** The first line of the web UI mode; the machine mode never prints it. */
    private static final String WEB_UI_BANNER = "Azur Updater:";
    /** Code shown to the player when the shipped updater has no sync mode. */
    static final String STALE = "stale";

    /** Queued by the reader thread after the last line: the process closed its output. Compared by identity. */
    private static final String EOF = new String("<eof>");

    private final Path binary;
    private final ProcessStarter starter;
    private final Duration firstEventTimeout;
    private final Duration idleTimeout;

    public PackSyncService(Path binary, ProcessStarter starter) {
        this(binary, starter, FIRST_EVENT_TIMEOUT, IDLE_TIMEOUT);
    }

    PackSyncService(Path binary, ProcessStarter starter, Duration firstEventTimeout, Duration idleTimeout) {
        this.binary = binary;
        this.starter = starter;
        this.firstEventTimeout = firstEventTimeout;
        this.idleTimeout = idleTimeout;
    }

    /** Real processes; {@code updaterHome} keeps the updater's own state out of the user's config folders. */
    public static ProcessStarter systemStarter(Path updaterHome) {
        return command -> {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            Map<String, String> env = builder.environment();
            env.put("AZUR_UPDATER_HOME", updaterHome.toString());
            return builder.start();
        };
    }

    /** Brings {@code instance} to the pack of the server behind {@code invite}. */
    public Result sync(String serverUrl, Path instance, String invite, SyncProgress progress) throws IOException {
        List<String> command = new ArrayList<>(List.of(binary.toString(), "sync", "--instance", instance.toString(),
                "--invite", invite, "--json"));
        if (serverUrl != null && !serverUrl.isBlank()) {
            command.addAll(List.of("--server-url", serverUrl));
        }
        return run(command, progress);
    }

    /** Re-hashes the installed pack files without network access. */
    public Result verify(Path instance) throws IOException {
        return run(List.of(binary.toString(), "verify", "--instance", instance.toString(), "--json"),
                (phase, file, done, total) -> { });
    }

    /** True for the web UI greeting, which means the binary predates the launcher's machine mode. */
    static boolean isWebUiBanner(String line) {
        return line != null && line.strip().startsWith(WEB_UI_BANNER);
    }

    private Result run(List<String> command, SyncProgress progress) throws IOException {
        Process process = starter.start(command);
        String packVersion = "";
        SyncEvent error = null;
        BlockingQueue<String> output = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> pump(process, output), "azur-updater-output");
        reader.setDaemon(true);
        reader.start();
        boolean answered = false;
        try {
            while (true) {
                Duration wait = answered ? idleTimeout : firstEventTimeout;
                String line = output.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
                if (line == null) {
                    process.destroyForcibly();
                    return answered
                            ? new Result(-1, "network", "the updater stopped responding", packVersion)
                            : new Result(-1, STALE,
                                    "the updater did not answer in machine mode (outdated binary)", packVersion);
                }
                if (line == EOF) {
                    break;
                }
                if (isWebUiBanner(line)) {
                    process.destroyForcibly();
                    return new Result(-1, STALE,
                            "the updater started its web UI instead of sync (outdated binary)", packVersion);
                }
                answered = true;
                Optional<SyncEvent> parsed = SyncEvent.parse(line);
                if (parsed.isEmpty()) {
                    continue;
                }
                SyncEvent event = parsed.get();
                switch (event.event()) {
                    case "start", "done" -> packVersion = event.packVersion() == null ? packVersion : event.packVersion();
                    case "download", "install" -> progress.onProgress(event.event(), event.file(), event.done(), event.total());
                    case "error" -> error = event;
                    default -> { }
                }
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the updater", e);
        }
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the updater", e);
        }
        if (exit == 0) {
            return new Result(0, "", "", packVersion);
        }
        if (error != null) {
            return new Result(exit, nz(error.code()), nz(error.message()), packVersion);
        }
        return new Result(exit, "unknown", "updater exited with code " + exit, packVersion);
    }

    /** Copies the merged output until EOF; the daemon thread must never keep the launcher alive. */
    private static void pump(Process process, BlockingQueue<String> output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.offer(line);
            }
        } catch (IOException ignored) {
            // the process was killed or exited: waitFor reports the exit code
        } finally {
            output.offer(EOF);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
