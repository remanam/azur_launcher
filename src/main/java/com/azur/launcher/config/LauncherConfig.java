package com.azur.launcher.config;

import java.nio.file.Path;

/** Persisted launcher settings (JSON via Gson; unknown fields from older files are ignored). */
public class LauncherConfig {

    private String versionId = "";
    private String gameDir = "";
    private int downloadThreads = 16;
    private String javaPath = "";
    private boolean autoDetectJava = true;
    private int maxMemoryMb = 4096;
    private int minMemoryMb = 512;
    private int windowWidth = 854;
    private int windowHeight = 480;
    private String jvmArgs = "";
    private boolean darkMode = false;
    private String language = "auto";

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getGameDir() {
        return gameDir;
    }

    public void setGameDir(String gameDir) {
        this.gameDir = gameDir;
    }

    public int getDownloadThreads() {
        return Math.max(1, Math.min(64, downloadThreads));
    }

    public void setDownloadThreads(int downloadThreads) {
        this.downloadThreads = Math.max(1, Math.min(64, downloadThreads));
    }

    public String getJavaPath() {
        return javaPath;
    }

    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath;
    }

    public boolean isAutoDetectJava() {
        return autoDetectJava;
    }

    public void setAutoDetectJava(boolean autoDetectJava) {
        this.autoDetectJava = autoDetectJava;
    }

    public int getMaxMemoryMb() {
        return maxMemoryMb;
    }

    public void setMaxMemoryMb(int maxMemoryMb) {
        this.maxMemoryMb = maxMemoryMb;
    }

    public int getMinMemoryMb() {
        return minMemoryMb;
    }

    public void setMinMemoryMb(int minMemoryMb) {
        this.minMemoryMb = minMemoryMb;
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public void setWindowWidth(int windowWidth) {
        this.windowWidth = windowWidth;
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    public void setWindowHeight(int windowHeight) {
        this.windowHeight = windowHeight;
    }

    public String getJvmArgs() {
        return jvmArgs;
    }

    public void setJvmArgs(String jvmArgs) {
        this.jvmArgs = jvmArgs;
    }

    public boolean isDarkMode() {
        return darkMode;
    }

    public void setDarkMode(boolean darkMode) {
        this.darkMode = darkMode;
    }

    /** {@code auto}, {@code ru} or {@code en}. */
    public String getLanguage() {
        return language == null || language.isBlank() ? "auto" : language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Path gameDirOrNull() {
        return (gameDir == null || gameDir.isBlank()) ? null : Path.of(gameDir);
    }
}
