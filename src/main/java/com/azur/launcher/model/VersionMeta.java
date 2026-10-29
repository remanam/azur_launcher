package com.azur.launcher.model;

import java.util.List;
import java.util.Map;

public class VersionMeta {

    private String id;
    private String type;
    private String mainClass;
    private String assets;
    private String inheritsFrom;
    private String jar;

    private AssetIndex assetIndex;
    private Map<String, DownloadInfo> downloads;
    private List<Library> libraries;
    private Arguments arguments;
    private String minecraftArguments;
    private JavaVersion javaVersion;

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getMainClass() {
        return mainClass;
    }

    public String getAssets() {
        return assets;
    }

    public String getInheritsFrom() {
        return inheritsFrom;
    }

    public String getJar() {
        return jar;
    }

    public AssetIndex getAssetIndex() {
        return assetIndex;
    }

    public Map<String, DownloadInfo> getDownloads() {
        return downloads;
    }

    public List<Library> getLibraries() {
        return libraries;
    }

    public Arguments getArguments() {
        return arguments;
    }

    public String getMinecraftArguments() {
        return minecraftArguments;
    }

    public JavaVersion getJavaVersion() {
        return javaVersion;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    public void setAssets(String assets) {
        this.assets = assets;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setJar(String jar) {
        this.jar = jar;
    }

    public void setAssetIndex(AssetIndex assetIndex) {
        this.assetIndex = assetIndex;
    }

    public void setDownloads(Map<String, DownloadInfo> downloads) {
        this.downloads = downloads;
    }

    public void setLibraries(List<Library> libraries) {
        this.libraries = libraries;
    }

    public void setArguments(Arguments arguments) {
        this.arguments = arguments;
    }

    public void setMinecraftArguments(String minecraftArguments) {
        this.minecraftArguments = minecraftArguments;
    }

    public void setJavaVersion(JavaVersion javaVersion) {
        this.javaVersion = javaVersion;
    }

    public DownloadInfo getClientDownload() {
        return downloads == null ? null : downloads.get("client");
    }

    public static class JavaVersion {
        private String component;
        private int majorVersion;

        public String getComponent() {
            return component;
        }

        public int getMajorVersion() {
            return majorVersion;
        }
    }
}
