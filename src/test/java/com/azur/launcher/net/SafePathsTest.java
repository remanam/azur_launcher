package com.azur.launcher.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafePathsTest {

    @Test
    void acceptsNestedAndRejectsTraversal(@TempDir Path base) throws IOException {
        assertEquals(base.toAbsolutePath().normalize().resolve("a/b.jar"), SafePaths.resolve(base, "a/b.jar"));
        assertThrows(IOException.class, () -> SafePaths.resolve(base, "../x"));
        assertThrows(IOException.class, () -> SafePaths.resolve(base, "a/../../x"));
        assertThrows(IOException.class, () -> SafePaths.resolve(base, "..\\x"));
        assertThrows(IOException.class, () -> SafePaths.resolve(base, "/etc/passwd"));
        assertThrows(IOException.class, () -> SafePaths.resolve(base, "C:/x"));
        assertThrows(IOException.class, () -> SafePaths.resolve(base, ""));
    }
}
