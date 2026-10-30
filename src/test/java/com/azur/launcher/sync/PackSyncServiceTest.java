package com.azur.launcher.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackSyncServiceTest {

    @TempDir
    Path dir;

    /** A process that prints canned output and exits with a fixed code. */
    private static final class FakeProcess extends Process {
        private final String output;
        private final int exit;

        FakeProcess(String output, int exit) {
            this.output = output;
            this.exit = exit;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return exit;
        }

        @Override
        public int exitValue() {
            return exit;
        }

        @Override
        public void destroy() {
        }
    }

    /** A process that never answers: the launcher must kill it instead of waiting for a machine-mode line. */
    private static final class SilentProcess extends Process {
        private final PipedInputStream in = new PipedInputStream();
        private final java.io.PipedOutputStream writer = connect();
        private volatile boolean killed;

        /** An unconnected pipe fails at once, which would look like EOF; connected and never written it stays silent. */
        private java.io.PipedOutputStream connect() {
            try {
                return new java.io.PipedOutputStream(in);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 1;
        }

        @Override
        public int exitValue() {
            return 1;
        }

        @Override
        public void destroy() {
        }

        @Override
        public Process destroyForcibly() {
            killed = true;
            try {
                in.close();
            } catch (IOException ignored) {
                // the reader thread is ending anyway
            }
            return this;
        }

        boolean killed() {
            return killed;
        }
    }

    @Test
    void successParsesProgressAndVersion() throws IOException {
        List<String> seen = new ArrayList<>();
        List<List<String>> commands = new ArrayList<>();
        String out = "noise line\n{\"event\":\"start\",\"pack_version\":\"7\"}\n"
                + "{\"event\":\"download\",\"file\":\"mods/a.jar\",\"done\":5,\"total\":10}\n"
                + "{\"event\":\"install\",\"file\":\"mods/a.jar\",\"done\":1,\"total\":1}\n"
                + "{broken\n{\"event\":\"done\",\"pack_version\":\"7\"}\n";
        PackSyncService service = new PackSyncService(Path.of("/bin/azur-updater"), command -> {
            commands.add(command);
            return new FakeProcess(out, 0);
        });
        PackSyncService.Result result = service.sync("https://dist.example:8780", dir, "azur1.CODE",
                (phase, file, done, total) -> seen.add(phase + ":" + file + ":" + done + "/" + total));
        assertTrue(result.ok());
        assertEquals("7", result.packVersion());
        assertEquals(List.of("download:mods/a.jar:5/10", "install:mods/a.jar:1/1"), seen);
        assertEquals(List.of("/bin/azur-updater", "sync", "--instance", dir.toString(), "--invite", "azur1.CODE",
                "--json", "--server-url", "https://dist.example:8780"), commands.get(0));
    }

    @Test
    void errorEventGivesTheCodeAndExit() throws IOException {
        String out = "{\"event\":\"start\",\"pack_version\":\"7\"}\n"
                + "{\"event\":\"error\",\"code\":\"signature\",\"message\":\"bad\",\"exit\":5}\n";
        PackSyncService service = new PackSyncService(Path.of("u"), command -> new FakeProcess(out, 5));
        PackSyncService.Result result = service.sync("", dir, "azur1.X", (p, f, d, t) -> { });
        assertFalse(result.ok());
        assertEquals(5, result.exit());
        assertEquals("signature", result.code());
        assertEquals("bad", result.message());
    }

    @Test
    void crashWithoutEventIsUnknown() throws IOException {
        PackSyncService service = new PackSyncService(Path.of("u"), command -> new FakeProcess("panic\n", 2));
        PackSyncService.Result result = service.verify(dir);
        assertEquals("unknown", result.code());
        assertEquals(2, result.exit());
    }

    @Test
    void noServerUrlMeansNoFlag() throws IOException {
        List<List<String>> commands = new ArrayList<>();
        new PackSyncService(Path.of("u"), command -> {
            commands.add(command);
            return new FakeProcess("", 0);
        }).sync("  ", dir, "azur1.X", (p, f, d, t) -> { });
        assertFalse(commands.get(0).contains("--server-url"));
    }

    @Test
    void eventParserIgnoresNoise() {
        assertTrue(SyncEvent.parse("hello").isEmpty());
        assertTrue(SyncEvent.parse("{}").isEmpty());
        assertTrue(SyncEvent.parse("{not json").isEmpty());
        assertEquals("done", SyncEvent.parse(" {\"event\":\"done\"} ").get().event());
    }

    /** A binary dropped without the marker (older launcher) is replaced once, otherwise a stale updater wins forever. */
    @Test
    void localCopyWithoutMarkerIsAdoptedByTheJar(@TempDir Path dir) throws IOException {
        String name = "azur-updater-linux-amd64";
        Files.writeString(dir.resolve(name), "stale-from-an-older-launcher");
        assertEquals("bin-A", text(UpdaterBinary.refreshLocalCopy(dir, name,
                "bin-A".getBytes(StandardCharsets.UTF_8))));
    }

    /** The web UI greeting means the binary predates the machine mode: report it and kill the process. */
    @Test
    void webUiBannerIsReportedAsAStaleBinary() throws IOException {
        String banner = "Azur Updater: http://127.0.0.1:54321/?k=secret\n(окно браузера открывается само; Ctrl+C - выход)\n";
        PackSyncService service = new PackSyncService(Path.of("u"),
                command -> new FakeProcess(banner, 0));
        PackSyncService.Result result = service.sync("", dir, "azur1.X", (p, f, d, t) -> { });
        assertFalse(result.ok());
        assertEquals(PackSyncService.STALE, result.code());
        assertEquals(-1, result.exit());
    }

    /** No answer at all must not hang the launcher: the process is killed after the first-event timeout. */
    @Test
    void silentUpdaterIsKilledAfterTheFirstEventTimeout() throws IOException {
        SilentProcess silent = new SilentProcess();
        PackSyncService service = new PackSyncService(Path.of("u"), command -> silent,
                Duration.ofMillis(50), Duration.ofMillis(50));
        PackSyncService.Result result = service.sync("", dir, "azur1.X", (p, f, d, t) -> { });
        assertFalse(result.ok());
        assertEquals(PackSyncService.STALE, result.code());
        assertTrue(silent.killed());
    }

    /** The copy in the data folder follows the jar, but never overwrites what the updater put there itself. */
    @Test
    void localCopyFollowsTheJarWithoutKillingSelfUpdate(@TempDir Path dir) throws IOException {
        String name = "azur-updater-linux-amd64";
        byte[] shipped = "bin-A".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] newer = "bin-C".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("bin-A", text(UpdaterBinary.refreshLocalCopy(dir, name, shipped)));
        assertEquals("bin-A", text(UpdaterBinary.refreshLocalCopy(dir, name, shipped)));

        // the updater replaced itself: the marker still names the shipped binary, so this copy wins
        Files.writeString(dir.resolve(name), "bin-B");
        assertEquals("bin-B", text(UpdaterBinary.refreshLocalCopy(dir, name, shipped)));

        // a newer jar ships another binary: our own copy (equal to the marker) is replaced
        Files.writeString(dir.resolve(name), "bin-A");
        assertEquals("bin-C", text(UpdaterBinary.refreshLocalCopy(dir, name, newer)));
        assertEquals("bin-C", text(UpdaterBinary.refreshLocalCopy(dir, name, newer)));

        // jar without binaries, nothing installed: nothing to run
        assertTrue(UpdaterBinary.refreshLocalCopy(dir.resolve("empty"), name, null) == null);
    }

    private static String text(Path file) throws IOException {
        return new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void binaryBesideTheJarIsFound(@TempDir Path dir) throws IOException {
        Path app = Files.createDirectories(dir.resolve("app"));
        Path jar = Files.writeString(app.resolve("azur-launcher-all.jar"), "x");
        String name = "azur-updater-linux-amd64";
        assertEquals(Optional.empty(), UpdaterBinary.besideJar(jar, name));
        Path inUpdaterDir = Files.writeString(Files.createDirectories(app.resolve("updater")).resolve(name), "x");
        assertEquals(Optional.of(inUpdaterDir), UpdaterBinary.besideJar(jar, name));
        assertEquals(Optional.empty(), UpdaterBinary.besideJar(null, name));
        assertEquals(Optional.empty(), UpdaterBinary.besideJar(dir.resolve("missing.jar"), name));
    }

    @Test
    void binaryNamesMatchTheGoBuild() throws IOException {
        assertEquals("azur-updater-windows-amd64.exe", UpdaterBinary.fileName("Windows 11", "amd64"));
        assertEquals("azur-updater-darwin-arm64", UpdaterBinary.fileName("Mac OS X", "aarch64"));
        assertEquals("azur-updater-linux-amd64", UpdaterBinary.fileName("Linux", "x86_64"));
        Path bin = Files.createDirectories(dir.resolve("bin"));
        assertEquals(Optional.empty(), UpdaterBinary.locate("", bin, "azur-updater-linux-amd64"));
        Path file = Files.writeString(bin.resolve("azur-updater-linux-amd64"), "x");
        assertEquals(Optional.of(file), UpdaterBinary.locate("", bin, "azur-updater-linux-amd64"));
        assertEquals(Optional.empty(), UpdaterBinary.locate(dir.resolve("missing").toString(), bin, "x"));
        assertEquals(Optional.of(file), UpdaterBinary.locate(file.toString(), bin, "x"));
    }
}
