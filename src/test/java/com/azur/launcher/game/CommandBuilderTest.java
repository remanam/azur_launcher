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
    void commandComesFromProfileAndManifestAsAList() {
        LaunchProfile profile = new LaunchProfile();
        profile.setVersionId("neoforge-21.1.264");
        profile.setJavaExecutable(java.nio.file.Path.of("/jdk/bin/java"));
        profile.setNativesDir(java.nio.file.Path.of("/n"));
        profile.setClasspath("a.jar:b.jar");
        profile.setMaxMemoryMb(6144);
        profile.setMinMemoryMb(2048);
        profile.setExtraJvmArgs(List.of("-XX:+UseG1GC", "-Dname=a b"));
        profile.setAccount(new LaunchContext() {
            public String username() {
                return "Alex";
            }

            public String uuid() {
                return "u-1";
            }

            public String accessToken() {
                return "tok";
            }

            public String userType() {
                return "msa";
            }
        });
        com.azur.launcher.model.VersionMeta meta = new com.google.gson.Gson().fromJson("""
                {"mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher",
                 "arguments":{"jvm":["-Djava.library.path=${natives_directory}","-cp","${classpath}"],
                              "game":["--username","${auth_player_name}","--accessToken","${auth_access_token}"]}}
                """, com.azur.launcher.model.VersionMeta.class);
        List<String> cmd = CommandBuilder.build(profile, meta);
        assertEquals("/jdk/bin/java", cmd.get(0));
        assertEquals(List.of("-Xmx6144m", "-Xms2048m"), cmd.subList(1, 3));
        assertEquals(true, cmd.contains("-Dname=a b"), "kept as one argument, not split by spaces");
        int main = cmd.indexOf("cpw.mods.bootstraplauncher.BootstrapLauncher");
        assertEquals(true, cmd.indexOf("-cp") < main && cmd.indexOf("-XX:+UseG1GC") < main);
        assertEquals(List.of("--username", "Alex", "--accessToken", "tok"), cmd.subList(main + 1, cmd.size()));
        assertEquals("a.jar:b.jar", cmd.get(cmd.indexOf("-cp") + 1));
    }

    @Test
    void accessTokenIsMaskedInTheLoggedCommand() {
        String logged = GameLauncher.maskSecrets(List.of("java", "--accessToken", "secret-token", "--uuid", "u"));
        assertEquals("java --accessToken *** --uuid u", logged);
    }
}
