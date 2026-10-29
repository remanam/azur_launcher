package com.azur.launcher.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CommandBuilderTest {

    @Test
    void splitsPlainArgumentsAndKeepsQuotedGroups() {
        assertEquals(List.of("-Xss1m", "-Dname=a b", "-XX:+UseG1GC"),
                CommandBuilder.splitPlain("-Xss1m \"-Dname=a b\"   -XX:+UseG1GC"));
        assertEquals(List.of(), CommandBuilder.splitPlain("  "));
    }

    @Test
    void replacesPlaceholders() {
        assertEquals("--username Alex", CommandBuilder.replacePlaceholders("--username ${auth_player_name}",
                Map.of("${auth_player_name}", "Alex")));
    }

    @Test
    void accessTokenIsMaskedInTheLoggedCommand() {
        String logged = GameLauncher.maskSecrets(List.of("java", "--accessToken", "secret-token", "--uuid", "u"));
        assertEquals("java --accessToken *** --uuid u", logged);
    }
}
