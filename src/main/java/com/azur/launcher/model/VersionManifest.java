package com.azur.launcher.model;

import java.util.List;

public class VersionManifest {

    private Latest latest;
    private List<VersionEntry> versions;

    public Latest getLatest() {
        return latest;
    }

    public List<VersionEntry> getVersions() {
        return versions;
    }

    public static class Latest {
        private String release;
        private String snapshot;

        public String getRelease() {
            return release;
        }

        public String getSnapshot() {
            return snapshot;
        }
    }

    public static class VersionEntry {
        private String id;
        private String type;
        private String url;
        private String time;
        private String releaseTime;
        private String sha1;
        private int complianceLevel;

        public String getId() {
            return id;
        }

        public String getType() {
            return type;
        }

        public String getUrl() {
            return url;
        }

        public String getTime() {
            return time;
        }

        public String getReleaseTime() {
            return releaseTime;
        }

        public String getSha1() {
            return sha1;
        }

        public int getComplianceLevel() {
            return complianceLevel;
        }

        @Override
        public String toString() {
            return id + "  [" + type + "]";
        }
    }
}
