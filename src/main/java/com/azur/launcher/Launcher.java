package com.azur.launcher;

import com.azur.launcher.config.AppDirs;
import com.azur.launcher.update.RestartWait;
import com.azur.launcher.update.Restarter;
import com.azur.launcher.update.UpdateStore;

import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Bootstrap and the jpackage main class (it never changes, so a self-update needs no new installer). If
 * {@code <data>/updates/current.jar} is newer than this embedded jar, verified and not rolled back, the app is run
 * from that jar in an isolated class loader (only JDK classes are shared); otherwise {@link LauncherMain} runs here.
 * Keep this class free of JavaFX and of anything that changes often: it is the part of the wrapper that cannot be updated.
 */
public final class Launcher {

    /** Version of the embedded wrapper, set before the app starts (the app may run from a newer jar). */
    public static final String WRAPPER_PROPERTY = "azur.wrapper.version";

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty(WRAPPER_PROPERTY, AppVersion.WRAPPER);
        UpdateStore store = UpdateStore.at(AppDirs.system().root().resolve("updates"));
        Optional<Path> chosen = Optional.empty();
        try {
            RestartWait.awaitParent(System.getenv(Restarter.WAIT_ENV));
            chosen = store.selectWithRetry(AppVersion.APP, 5, Thread::sleep);
        } catch (RuntimeException e) {
            System.err.println("Update store ignored: " + e);
        }
        if (chosen.isPresent()) {
            try {
                runFrom(chosen.get(), args);
                return;
            } catch (ReflectiveOperationException | MalformedURLException | LinkageError e) {
                System.err.println("Updated launcher failed to start, rolling back: " + e);
                store.rollbackNow();
            }
        }
        LauncherMain.main(args);
    }

    private static void runFrom(Path jar, String[] args) throws ReflectiveOperationException, MalformedURLException {
        URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        try {
            loader.loadClass("com.azur.launcher.LauncherMain").getMethod("main", String[].class)
                    .invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }
}
