package com.azur.launcher.update;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Starts a new copy of this launcher so the bootstrap can apply a staged update. On Windows the running process keeps
 * {@code current.jar} open (its URLClassLoader), and the bootstrap of the copy cannot move it and would silently run
 * the old embedded jar again; so the loader is closed right before the copy starts, and the caller halts the JVM.
 * The command is a plain argument list (ProcessBuilder quotes spaces and Cyrillic itself, no cmd.exe, no console).
 */
public final class Restarter {

    /** What the JVM knows about itself. {@code sep} is the file separator of the OS ("\\" on Windows). */
    public record Env(boolean windows, String sep, String appPath, String javaHome, String classPath,
                      Optional<String> command, List<String> arguments) {
    }

    /** Starts a process; false when it could not be started (tests replace it). */
    public interface Starter {
        boolean start(List<String> command);
    }

    static final String MAIN_CLASS = "com.azur.launcher.Launcher";

    private Restarter() {
    }

    public static Env currentEnv() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        return new Env(windows, System.getProperty("file.separator", "/"), System.getProperty("jpackage.app-path", ""),
                System.getProperty("java.home", ""), System.getProperty("java.class.path", ""), info.command(),
                info.arguments().map(List::of).orElse(List.of()));
    }

    /**
     * The command that starts this launcher again. The jpackage launcher (property {@code jpackage.app-path}) comes
     * first; then the process command with its arguments (the JVM does not report arguments on Windows); then
     * {@code javaw -cp <class path> Launcher}. Empty when none of them is known.
     */
    public static Optional<List<String>> appCommand(Env env, Predicate<String> exists) {
        if (!env.appPath().isBlank()) {
            return Optional.of(List.of(env.appPath()));
        }
        if (env.command().isPresent() && !env.arguments().isEmpty()) {
            List<String> line = new ArrayList<>();
            line.add(env.command().get());
            line.addAll(env.arguments());
            return Optional.of(line);
        }
        if (env.javaHome().isBlank() || env.classPath().isBlank()) {
            return Optional.empty();
        }
        String bin = env.javaHome() + env.sep() + "bin" + env.sep();
        String java = env.windows() ? (exists.test(bin + "javaw.exe") ? bin + "javaw.exe" : bin + "java.exe") : bin + "java";
        return Optional.of(List.of(java, "-cp", env.classPath(), MAIN_CLASS));
    }

    /** Starts the copy; the caller halts right after. False when the command is unknown or did not start. */
    public static boolean relaunch() {
        return relaunch(currentEnv(), path -> new java.io.File(path).isFile(), command -> {
            releaseOwnJar();
            return start(command);
        });
    }

    /** Closes the class loader of the running jar so Windows lets the copy replace {@code current.jar}. */
    static void releaseOwnJar() {
        if (Restarter.class.getClassLoader() instanceof java.net.URLClassLoader loader) {
            try {
                loader.close();
            } catch (IOException ignored) {
                // the copy then falls back to the embedded jar, nothing worse
            }
        }
    }

    static boolean start(List<String> command) {
        try {
            new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Same as {@link #relaunch()} with the environment and the starter given (tests). */
    public static boolean relaunch(Env env, Predicate<String> exists, Starter starter) {
        Optional<List<String>> line = appCommand(env, exists);
        return line.isPresent() && starter.start(line.get());
    }
}
