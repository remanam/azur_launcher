package com.azur.launcher.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
