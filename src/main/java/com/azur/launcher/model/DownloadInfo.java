package com.azur.launcher.model;

public class DownloadInfo {

    private String sha1;
    private long size;
    private String url;
    private String path;

    public String getSha1() {
        return sha1;
    }

    public long getSize() {
        return size;
    }

    public String getUrl() {
        return url;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return "DownloadInfo{url='" + url + "', sha1='" + sha1 + "', size=" + size + "}";
    }
}
