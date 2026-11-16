package com.azur.launcher.game;


import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class LaunchProfile {

    public static final String LAUNCHER_NAME = "Azur";
    public static final String LAUNCHER_VERSION = "1.0.0";

    private String versionId = "";
    private String versionType = "release";

    private Path javaExecutable;
    private Path gameDir;
    private Path assetsDir;
    private Path librariesDir;
    private Path nativesDir;
    private Path clientJar;

    private String classpath = "";
    private String assetIndexName = "";

    private int maxMemoryMb = 4096;
    private int minMemoryMb = 512;
    private java.util.List<String> extraJvmArgs = java.util.List.of();

    private int windowWidth;
    private int windowHeight;

    private LaunchContext account;

    public Map<String, String> toPlaceholders() {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("${auth_player_name}", Objects.isNull(account) ? "Player" : account.username());
        ph.put("${version_name}", versionId);
        ph.put("${version_type}", versionType);
        ph.put("${game_directory}", str(gameDir));
        ph.put("${assets_root}", str(assetsDir));
        ph.put("${assets_index_name}", assetIndexName);
        ph.put("${auth_uuid}", Objects.isNull(account) ? "" : account.uuid());
        ph.put("${auth_access_token}", Objects.isNull(account) ? "0" : account.accessToken());
        ph.put("${auth_session}", Objects.isNull(account) ? "0" : account.accessToken());
        ph.put("${user_type}", Objects.isNull(account) ? "legacy" : account.userType());
        ph.put("${natives_directory}", str(nativesDir));
        ph.put("${library_directory}", str(librariesDir));
        ph.put("${classpath}", classpath);
        ph.put("${classpath_separator}", File.pathSeparator);
        ph.put("${launcher_name}", LAUNCHER_NAME);
        ph.put("${launcher_version}", LAUNCHER_VERSION);
        ph.put("${clientid}", Objects.isNull(account) ? "" : blankToEmpty(account.clientId()));
        ph.put("${auth_xuid}", Objects.isNull(account) ? "" : blankToEmpty(account.xuid()));
        ph.put("${user_properties}", Objects.isNull(account) ? "{}" : blankToDefault(account.userProperties(), "{}"));
        ph.put("${resolution_width}", String.valueOf(windowWidth));
        ph.put("${resolution_height}", String.valueOf(windowHeight));
        ph.put("${quickPlayPath}", "");
        ph.put("${quickPlaySingleplayer}", "");
        ph.put("${quickPlayMultiplayer}", "");
        ph.put("${quickPlayRealms}", "");
        return ph;
    }

    private static String str(Path path) {
        return Objects.isNull(path) ? "" : path.toString();
    }

    private static String blankToEmpty(String value) {
        return Objects.isNull(value) ? "" : value;
    }

    private static String blankToDefault(String value, String fallback) {
        return (Objects.isNull(value) || value.isBlank()) ? fallback : value;
    }

    // ------------------------------------------------------------------ getters / setters

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getVersionType() {
        return versionType;
    }

    public void setVersionType(String versionType) {
        this.versionType = versionType;
    }

    public Path getJavaExecutable() {
        return javaExecutable;
    }

    public void setJavaExecutable(Path javaExecutable) {
        this.javaExecutable = javaExecutable;
    }

    public Path getGameDir() {
        return gameDir;
    }

    public void setGameDir(Path gameDir) {
        this.gameDir = gameDir;
    }

    public Path getAssetsDir() {
        return assetsDir;
    }

    public void setAssetsDir(Path assetsDir) {
        this.assetsDir = assetsDir;
    }

    public Path getLibrariesDir() {
        return librariesDir;
    }

    public void setLibrariesDir(Path librariesDir) {
        this.librariesDir = librariesDir;
    }

    public Path getNativesDir() {
        return nativesDir;
    }

    public void setNativesDir(Path nativesDir) {
        this.nativesDir = nativesDir;
    }

    public Path getClientJar() {
        return clientJar;
    }

    public void setClientJar(Path clientJar) {
        this.clientJar = clientJar;
    }

    public String getClasspath() {
        return classpath;
    }

    public void setClasspath(String classpath) {
        this.classpath = classpath;
    }

    public String getAssetIndexName() {
        return assetIndexName;
    }

    public void setAssetIndexName(String assetIndexName) {
        this.assetIndexName = assetIndexName;
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

    public java.util.List<String> getExtraJvmArgs() {
        return extraJvmArgs;
    }

    public void setExtraJvmArgs(java.util.List<String> extraJvmArgs) {
        this.extraJvmArgs = extraJvmArgs;
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

    public LaunchContext getAccount() {
        return account;
    }

    public void setAccount(LaunchContext account) {
        this.account = account;
    }
}
