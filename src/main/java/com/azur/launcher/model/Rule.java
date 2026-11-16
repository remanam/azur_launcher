package com.azur.launcher.model;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Rule {

    private String action;
    private Os os;

    private JsonElement features;

    public String getAction() {
        return action;
    }

    public Os getOs() {
        return os;
    }

    public List<String> getFeatures() {
        if (Objects.isNull(features) || features.isJsonNull()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        if (features.isJsonArray()) {
            for (JsonElement element : features.getAsJsonArray()) {
                if (Objects.nonNull(element) && element.isJsonPrimitive()) {
                    result.add(element.getAsString());
                }
            }
        } else if (features.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : features.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (Objects.isNull(value) || !value.isJsonPrimitive()) {
                    continue;
                }
                boolean enabled = value.getAsJsonPrimitive().isBoolean()
                        ? value.getAsBoolean()
                        : Boolean.parseBoolean(value.getAsString());
                if (enabled) {
                    result.add(entry.getKey());
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    /** {"name": "windows", "version": "^10\\.", "arch": "x86"} */
    public static class Os {
        private String name;
        private String version;
        private String arch;

        public String getName() {
            return name;
        }

        public String getVersion() {
            return version;
        }

        public String getArch() {
            return arch;
        }
    }
}
