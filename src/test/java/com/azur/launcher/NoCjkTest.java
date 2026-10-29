package com.azur.launcher;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** The owner's rule: no Chinese anywhere in the project's sources, resources and documents. */
class NoCjkTest {

    private static boolean isCjk(int c) {
        return (c >= 0x3000 && c <= 0x303F) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0xFF00 && c <= 0xFFEF);
    }

    @Test
    void projectFilesContainNoCjkCharacters() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<Path> roots = List.of(Path.of("src"), Path.of("docs"), Path.of("tools"),
                Path.of("pom.xml"), Path.of("README.md"), Path.of("NOTICE"), Path.of("CLAUDE.md"));
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String text;
                    try {
                        text = Files.readString(file, StandardCharsets.UTF_8);
                    } catch (IOException binary) {
                        continue;
                    }
                    if (text.codePoints().anyMatch(NoCjkTest::isCjk)) {
                        offenders.add(file.toString());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "CJK found in: " + offenders);
    }
}
