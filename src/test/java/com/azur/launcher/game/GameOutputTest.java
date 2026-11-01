package com.azur.launcher.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameOutputTest {

    @Test
    void heavyOutputToFileDoesNotBlockAndTailWorks(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("g.log");
        Path src = dir.resolve("Spam.java");
        Files.writeString(src, "public class Spam { public static void main(String[] a) {"
                + " for (int i = 0; i < 2_000_000; i++) System.out.println(\"DEBUG line \" + i + \" xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\");"
                + " System.err.println(\"last-err\"); } }");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(javaBin, src.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        long t0 = System.nanoTime();
        Process p = pb.start();
        // the launcher side does nothing while the child writes
        assertTrue(p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "child must not block");
        assertEquals(0, p.exitValue());
        assertTrue(Files.size(log) > 100_000_000L);
        List<String> tail = LogFiles.tail(log, 30, 64 * 1024);
        assertEquals(30, tail.size());
        assertEquals("last-err", tail.get(29));
        assertTrue(tail.get(28).contains("DEBUG line 1999999"));
        System.out.println("spam ms " + (System.nanoTime() - t0) / 1_000_000);
    }

    @Test
    void readFromReturnsOnlyNewBytes(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("a.log");
        Files.writeString(f, "old\n");
        long mark = Files.size(f);
        Files.writeString(f, "Sound engine started\n", java.nio.file.StandardOpenOption.APPEND);
        assertTrue(LaunchWatchdog.isReadyLine(LogFiles.readFrom(f, mark, 1024)));
        assertEquals("", LogFiles.readFrom(f, Files.size(f), 1024));
    }
}
