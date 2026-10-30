package com.azur.launcher.pack;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A real publisher: builds {@code azur-dist} from tools/updater with the Go toolchain, generates a key, publishes a pack
 * and serves it on a random 127.0.0.1 port. Tests that use it are skipped when Go is not installed.
 */
final class GoPackFixture implements AutoCloseable {

    private static Path binary;

    final Path data;
    final Path key;
    final int port;
    private Process server;

    private GoPackFixture(Path work) throws IOException {
        data = work.resolve("data");
        key = work.resolve("signing.key");
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        run("keygen", "--key", key.toString());
    }

    /** Builds the Go publisher once; null when Go is missing. */
    static synchronized Path binary(Path buildDir) {
        if (binary != null) {
            return binary;
        }
        Path out = buildDir.resolve("azur-dist");
        try {
            Process p = new ProcessBuilder("go", "build", "-o", out.toString(), "./cmd/azur-dist")
                    .directory(Path.of("..", "tools", "updater").toFile()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor(180, TimeUnit.SECONDS) && p.exitValue() == 0) {
                binary = out;
            }
        } catch (IOException | InterruptedException e) {
            return null;
        }
        return binary;
    }

    static GoPackFixture create(Path work) throws IOException {
        return new GoPackFixture(work);
    }

    String run(String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(binary.toString(), args[0], "--data", data.toString(), "--key",
                key.toString()));
        cmd.addAll(List.of(args).subList(1, args.length));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IOException("azur-dist " + args[0] + " failed: " + out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        return out.strip();
    }

    void publish(Path pack) throws IOException {
        run("publish", "--pack", pack.toString());
    }

    String url() {
        return "http://127.0.0.1:" + port;
    }

    String invite(String name) throws IOException {
        return run("invite", "--name", name, "--url", url());
    }

    void serve() throws IOException {
        server = new ProcessBuilder(binary.toString(), "serve", "--data", data.toString(), "--listen",
                "127.0.0.1:" + port).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException notYet) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
        }
        throw new IOException("azur-dist serve did not start");
    }

    Path object(String sha256) {
        return data.resolve("objects").resolve(sha256);
    }

    static void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Override
    public void close() {
        if (server != null) {
            server.destroyForcibly();
        }
    }
}
