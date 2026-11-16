package com.azur.launcher.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Library {

    private String name;
    private String url;
    private Downloads downloads;
    private List<Rule> rules;
    private Map<String, String> natives;
    private Extract extract;

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public Downloads getDownloads() {
        return downloads;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public Map<String, String> getNatives() {
        return natives;
    }

    public Extract getExtract() {
        return extract;
    }

    public void setName(String name) {
        this.name = name;
    }

    private String[] coords() {
        return Objects.isNull(name) ? new String[0] : name.split(":");
    }

    public String getGroupId() {
        String[] c = coords();
        return c.length > 0 ? c[0] : "";
    }

    public String getArtifactId() {
        String[] c = coords();
        return c.length > 1 ? c[1] : "";
    }

    public String getVersion() {
        String[] c = coords();
        return c.length > 2 ? c[2] : "";
    }

    public String getDirectoryPath() {
        return getGroupId().replace('.', '/') + "/" + getArtifactId() + "/" + getVersion();
    }

    public String getArtifactFileName() {
        String version = getVersion();
        String[] c = coords();
        String extension = "jar";
        if (c.length > 2 && c[2].contains("@")) {
            version = c[2].substring(0, c[2].indexOf('@'));
            extension = c[2].substring(c[2].indexOf('@') + 1);
        }
        return getArtifactId() + "-" + version + "." + extension;
    }

    public boolean hasNatives() {
        return Objects.nonNull(natives) && !natives.isEmpty();
    }

    public static class Downloads {
        private DownloadInfo artifact;
        private Map<String, DownloadInfo> classifiers;

        public DownloadInfo getArtifact() {
            return artifact;
        }

        public Map<String, DownloadInfo> getClassifiers() {
            return classifiers;
        }
    }

    public static class Extract {
        private List<String> exclude;

        public List<String> getExclude() {
            return exclude;
        }
    }
}
