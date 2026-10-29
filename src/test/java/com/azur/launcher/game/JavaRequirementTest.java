package com.azur.launcher.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaRequirementTest {

    @Test
    void ruleByReleaseNumber() {
        assertEquals(8, JavaRequirement.ruleMajorFor("1.12.2"));
        assertEquals(17, JavaRequirement.ruleMajorFor("1.18.2"));
        assertEquals(17, JavaRequirement.ruleMajorFor("1.20.4"));
        assertEquals(21, JavaRequirement.ruleMajorFor("1.20.5"));
        assertEquals(21, JavaRequirement.ruleMajorFor("1.21.1"));
        assertEquals(21, JavaRequirement.ruleMajorFor("neoforge-21.1.172"));
        assertEquals(21, JavaRequirement.ruleMajorFor(null));
    }

    @Test
    void declaredJavaVersionWinsAndInheritanceIsFollowed(@TempDir Path versions) throws IOException {
        Path vanilla = versions.resolve("1.21.1");
        Files.createDirectories(vanilla);
        Files.writeString(vanilla.resolve("1.21.1.json"), "{\"javaVersion\":{\"majorVersion\":21}}");
        Path loader = versions.resolve("neoforge-21.1.1");
        Files.createDirectories(loader);
        Files.writeString(loader.resolve("neoforge-21.1.1.json"), "{\"inheritsFrom\":\"1.21.1\"}");

        assertEquals(21, JavaRequirement.requiredMajor("x", vanilla.resolve("1.21.1.json")));
        assertEquals(21, JavaRequirement.requiredMajor("1.12", loader.resolve("neoforge-21.1.1.json")));
        assertEquals(8, JavaRequirement.requiredMajor("1.12.2", versions.resolve("missing.json")));
    }
}
