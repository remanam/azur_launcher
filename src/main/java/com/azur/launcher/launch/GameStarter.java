package com.azur.launcher.launch;

/** Adapter between the server pages and the game launch (the existing {@code GameLauncher} flow). */
@FunctionalInterface
public interface GameStarter {

    /** Starts (or stops, when already running) the game for the request; reports problems itself. */
    void start(LaunchRequest request);
}
