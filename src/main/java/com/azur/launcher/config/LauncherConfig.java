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
    /** Seconds without log growth before the start counts as hung (0 = watchdog off). */
    private int hangSeconds = 120;
    /** Seconds the start may take without the "loaded" marker (0 = no limit). */
    private int startupLimitSeconds = 480;
    /** Hand the device key to the game in a 0600 file so reconnects sign in without the launcher (false = ticket only). */
    private boolean shareKeyWithGame = true;
    private boolean darkMode = false;
    private String lastServerId = "";
    private String language = "ru";
    /** Anonymous launcher telemetry (steps, error words, timings). ON by default (owner decision 2026-11-05); the player switches it off in Settings > Advanced. */
    private boolean telemetryEnabled = true;
    /** Random install id (32 hex), created when telemetry is switched on, erased when it is switched off. */
    private String telemetryId = "";
    /** The telemetry notice has been shown (wizard step 2, About, or an already finished wizard). null = old file, decided once at start. Nothing is sent before. */
    private Boolean telemetryNoticeShown;

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

    public boolean isTelemetryEnabled() {
        return telemetryEnabled;
    }

    public boolean isTelemetryNoticeShown() {
        return Boolean.TRUE.equals(telemetryNoticeShown);
    }

    /** Returns true when the flag changed (the caller saves the config). */
    public boolean markTelemetryNoticeShown() {
        boolean changed = !isTelemetryNoticeShown();
        telemetryNoticeShown = true;
        return changed;
    }

    /** Old files have no flag: an installation with a finished wizard has seen the notice, a fresh one has not. */
    public boolean migrateTelemetryNotice(boolean wizardFinished) {
        if (telemetryNoticeShown != null) {
            return false;
        }
        telemetryNoticeShown = wizardFinished;
        return wizardFinished;
    }

    /** Events leave the launcher only when telemetry is on AND the player has seen the notice. */
    public boolean isTelemetrySendable() {
        return telemetryEnabled && isTelemetryNoticeShown();
    }

    public String getTelemetryId() {
        if (telemetryEnabled && (telemetryId == null || telemetryId.isEmpty())) {
            telemetryId = java.util.UUID.randomUUID().toString().replace("-", "");
        }
        return telemetryId == null ? "" : telemetryId;
    }

    /** On: creates the install id when missing. Off: forgets it and returns the old id so the caller can ask the site to erase its rows. */
    public String setTelemetryEnabled(boolean value) {
        String old = telemetryId == null ? "" : telemetryId;
        telemetryEnabled = value;
        if (!value) {
            telemetryId = "";
            return old;
        }
        getTelemetryId();
        return "";
    }

    public boolean isShareKeyWithGame() {
        return shareKeyWithGame;
    }

    public void setShareKeyWithGame(boolean value) {
        this.shareKeyWithGame = value;
    }

    public int getHangSeconds() {
        return hangSeconds;
    }

    public int getStartupLimitSeconds() {
        return startupLimitSeconds;
    }

    public String getJvmArgs() {
        return jvmArgs;
    }

    public void setJvmArgs(String jvmArgs) {
        this.jvmArgs = jvmArgs;
    }

    public String getLastServerId() {
        return lastServerId == null ? "" : lastServerId;
    }

    public void setLastServerId(String id) {
        this.lastServerId = id == null ? "" : id;
    }

    public boolean isDarkMode() {
        return darkMode;
    }

    public void setDarkMode(boolean darkMode) {
        this.darkMode = darkMode;
    }

    /** {@code ru} (default) or {@code en}; legacy {@code auto} counts as {@code ru}. */
    public String getLanguage() {
        return language == null || language.isBlank() ? "ru" : language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Path gameDirOrNull() {
        return (gameDir == null || gameDir.isBlank()) ? null : Path.of(gameDir);
    }
}
