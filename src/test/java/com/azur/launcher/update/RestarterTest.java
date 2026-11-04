package com.azur.launcher.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class RestarterTest {

    private static final String WIN_EXE = "C:\\Users\\Иван Петров\\AppData\\Local\\Azur Launcher\\Azur Launcher.exe";
    private static final String WIN_JAVA_HOME = "C:\\Program Files\\Azur Launcher\\runtime";

    private static Restarter.Env win(String appPath, Optional<String> command, List<String> args, String classPath) {
        return new Restarter.Env(true, "\\", appPath, WIN_JAVA_HOME, classPath, command, args);
    }

    @Test
    void jpackageLauncherPathWinsAndIsKeptAsOneArgument() {
        Optional<List<String>> line = Restarter.appCommand(win(WIN_EXE, Optional.of("C:\\x\\java.exe"), List.of(), "a.jar"), p -> true);
        assertEquals(List.of(WIN_EXE), line.orElseThrow());
    }

    @Test
    void windowsReportsNoArgumentsSoJavawIsRebuiltFromJavaHome() {
        String classPath = "C:\\Users\\Иван Петров\\AppData\\Roaming\\Azur\\app\\azur-launcher.jar";
        Restarter.Env env = win("", Optional.of(WIN_JAVA_HOME + "\\bin\\java.exe"), List.of(), classPath);
        List<String> line = Restarter.appCommand(env, p -> p.endsWith("javaw.exe")).orElseThrow();
        assertEquals(List.of(WIN_JAVA_HOME + "\\bin\\javaw.exe", "-cp", classPath, Restarter.MAIN_CLASS), line);
        assertTrue(Restarter.appCommand(env, p -> false).orElseThrow().get(0).endsWith("\\bin\\java.exe"));
    }

    @Test
    void processCommandWithArgumentsIsUsedAsIs() {
        Restarter.Env env = new Restarter.Env(false, "/", "", "/jdk", "x.jar", Optional.of("/jdk/bin/java"),
                List.of("-jar", "/tmp/my launcher.jar"));
        assertEquals(List.of("/jdk/bin/java", "-jar", "/tmp/my launcher.jar"), Restarter.appCommand(env, p -> true).orElseThrow());
    }

    @Test
    void unknownEnvironmentGivesNoCommand() {
        Restarter.Env env = new Restarter.Env(true, "\\", "", "", "", Optional.empty(), List.of());
        assertTrue(Restarter.appCommand(env, p -> true).isEmpty());
        assertFalse(Restarter.relaunch(env, p -> true, command -> true));
    }

    @Test
    void relaunchHandsTheCommandToTheStarterAndReportsItsResult() {
        List<List<String>> started = new ArrayList<>();
        Restarter.Env env = win(WIN_EXE, Optional.empty(), List.of(), "");
        assertTrue(Restarter.relaunch(env, p -> true, command -> started.add(command)));
        assertEquals(List.of(List.of(WIN_EXE)), started);
        assertFalse(Restarter.relaunch(env, p -> true, command -> false));
    }

    @Test
    void realStartOfAMissingProgramFailsWithoutThrowing() {
        assertFalse(Restarter.start(List.of("/definitely/not/here/азур launcher")));
    }
}
