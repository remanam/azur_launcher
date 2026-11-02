package com.azur.launcher;

import com.azur.launcher.ui.LauncherApp;
import javafx.application.Application;

/** The real entry point of the app (the jar the Bootstrap {@link Launcher} chose, embedded or from {@code updates/}). */
public final class LauncherMain {

    private LauncherMain() {
    }

    public static void main(String[] args) {
        FxNatives.prepare();
        Application.launch(LauncherApp.class, args);
    }
}
