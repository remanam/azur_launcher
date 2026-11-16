package com.azur.launcher.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Runs a short OS tool; the secret goes through stdin, never through arguments. */
interface CommandRunner {

    record Result(int exit, String out) {
    }

    Result run(List<String> command, String stdin) throws IOException;

    CommandRunner SYSTEM = (command, stdin) -> {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (Objects.nonNull(stdin)) {
                p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Tool timed out: " + command.get(0));
            }
            return new Result(p.exitValue(), out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("Interrupted", e);
        }
    };
}
