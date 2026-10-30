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
    private static Path serversBinary;

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
        if (binary == null) {
            binary = build(buildDir, "azur-dist");
        }
        return binary;
    }

    private static synchronized Path serversBinary(Path buildDir) {
        if (serversBinary == null) {
            serversBinary = build(buildDir, "azur-servers");
        }
        return serversBinary;
    }

    private static Path build(Path buildDir, String cmd) {
        Path out = buildDir.resolve(cmd);
        try {
            Process p = new ProcessBuilder("go", "build", "-o", out.toString(), "./cmd/" + cmd)
                    .directory(Path.of("..", "tools", "updater").toFile()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(180, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
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

    /** Public key of this publisher, as the signed server list carries it ({@code azur-servers pubkey}). */
    String publicKey(Path buildDir) throws IOException {
        return servers(buildDir, "pubkey", "--key", key.toString());
    }

    /** Signs a servers.json document with this publisher's key ({@code azur-servers sign}). */
    byte[] signServers(Path buildDir, String json) throws IOException {
        Path in = Files.writeString(data.resolveSibling("servers.json"), json);
        Path out = data.resolveSibling("servers.signed.json");
        servers(buildDir, "sign", "--key", key.toString(), "--in", in.toString(), "--out", out.toString());
        return Files.readAllBytes(out);
    }

    private String servers(Path buildDir, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(serversBinary(buildDir).toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IOException("azur-servers " + args[0] + " failed: " + out);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        return out.strip();
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
