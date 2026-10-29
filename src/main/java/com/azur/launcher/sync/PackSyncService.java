package com.azur.launcher.sync;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs {@code azur-updater sync|verify} as a separate process and turns its JSON lines into progress and a result.
 * The command is an argument list (no shell). Blocking: call from a background thread.
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

    private final Path binary;
    private final ProcessStarter starter;

    public PackSyncService(Path binary, ProcessStarter starter) {
        this.binary = binary;
        this.starter = starter;
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

    private Result run(List<String> command, SyncProgress progress) throws IOException {
        Process process = starter.start(command);
        String packVersion = "";
        SyncEvent error = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
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
        }
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            process.destroy();
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

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
