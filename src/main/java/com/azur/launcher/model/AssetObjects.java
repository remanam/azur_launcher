package com.azur.launcher.model;

import java.util.Map;

public class AssetObjects {

    private Map<String, ObjectEntry> objects;
    private boolean virtual;
    private boolean map_to_resources;

    public Map<String, ObjectEntry> getObjects() {
        return objects;
    }

    public boolean isVirtual() {
        return virtual;
    }

    public boolean isMapToResources() {
        return map_to_resources;
    }

    public static class ObjectEntry {
        private String hash;
        private long size;

        public String getHash() {
            return hash;
        }

        public long getSize() {
            return size;
        }
    }
}
