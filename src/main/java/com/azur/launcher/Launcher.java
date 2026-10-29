package com.azur.launcher;

import com.azur.launcher.ui.LauncherApp;
import javafx.application.Application;

public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(LauncherApp.class, args);
    }
}
