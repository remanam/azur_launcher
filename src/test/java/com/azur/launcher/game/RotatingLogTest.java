package com.azur.launcher.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RotatingLogTest {

    @Test
    void rotatesAndKeepsOnlyTheConfiguredFiles(@TempDir Path dir) throws Exception {
        RotatingLog log = new RotatingLog(dir.resolve("logs/launcher.log"), 100, 2);
        for (int i = 0; i < 40; i++) {
            log.append("line number " + i);
        }
        assertTrue(Files.exists(dir.resolve("logs/launcher.log")));
        assertTrue(Files.exists(dir.resolve("logs/launcher.log.1")));
        assertTrue(Files.notExists(dir.resolve("logs/launcher.log.3")));
    }

    @Test
    void tailKeepsTheLastLines() {
        TailBuffer tail = new TailBuffer(3);
        for (int i = 1; i <= 5; i++) {
            tail.add("l" + i);
        }
        assertEquals(List.of("l3", "l4", "l5"), tail.lines());
    }
}
