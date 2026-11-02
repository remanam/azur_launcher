package com.azur.launcher.update;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Starts a new copy of this launcher (same executable and arguments) so the bootstrap can apply a staged update. */
public final class Restarter {

    private Restarter() {
    }

    /** The command line of this process, or empty when the JVM does not expose it. */
    public static Optional<List<String>> currentCommand(ProcessHandle.Info info) {
        Optional<String> command = info.command();
        if (command.isEmpty()) {
            return Optional.empty();
        }
        List<String> line = new ArrayList<>();
        line.add(command.get());
        info.arguments().ifPresent(args -> line.addAll(List.of(args)));
        return Optional.of(line);
    }

    /** Starts the copy; the caller exits right after. False when the command line is unknown or the start failed. */
    public static boolean relaunch() {
        Optional<List<String>> line = currentCommand(ProcessHandle.current().info());
        if (line.isEmpty()) {
            return false;
        }
        try {
            new ProcessBuilder(line.get()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
