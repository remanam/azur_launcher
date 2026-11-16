package com.azur.launcher.pack;

import com.google.gson.annotations.SerializedName;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Pack manifest v1 (the JSON that the publisher signs). Unknown fields are ignored. */
public record PackManifest(
        @SerializedName("manifest_version") int version,
        @SerializedName("pack_version") String packVersion,
        String mc,
        String neoforge,
        @SerializedName("min_updater_version") String minUpdater,
        List<Entry> files,
        List<String> retired,
        @SerializedName("user_owned") List<String> userOwned) {

    public static final int VERSION = 1;
    public static final long MAX_FILE_SIZE = 256L << 20;
    public static final int MAX_FILES = 5000;
    public static final String OVERWRITE = "overwrite";
    public static final String KEEP = "keep-if-exists";
    public static final String MERGE = "merge-list";

    /** One pack file. {@code url} is relative to the pack server ({@code /files/<sha256>}). */
    public record Entry(String path, String sha256, long size, String side, boolean optional, String policy,
            String url) {
    }

    public List<Entry> filesOrEmpty() {
        return Objects.isNull(files) ? List.of() : files;
    }

    public List<String> retiredOrEmpty() {
        return Objects.isNull(retired) ? List.of() : retired;
    }

    public List<String> userOwnedOrEmpty() {
        return Objects.isNull(userOwned) ? List.of() : userOwned;
    }

    /** Structural checks after the signature: version, limits, safe paths, hashes, known policies, no duplicates. */
    public void validate() throws PackException {
        if (version != VERSION) {
            throw bad("unknown manifest version " + version);
        }
        if (filesOrEmpty().size() > MAX_FILES) {
            throw bad("too many files in the manifest");
        }
        Set<String> seen = new HashSet<>();
        for (Entry f : filesOrEmpty()) {
            if (!PackPaths.isSafeRel(f.path())) {
                throw path(f.path());
            }
            if (!PackPaths.validHash(f.sha256()) || f.size() < 0 || f.size() > MAX_FILE_SIZE || Objects.isNull(f.url())) {
                throw bad("bad hash or size of " + f.path());
            }
            if (!seen.add(f.path().toLowerCase(Locale.ROOT))) {
                throw bad("duplicate path " + f.path());
            }
            if (!(OVERWRITE.equals(f.policy()) || KEEP.equals(f.policy()) || MERGE.equals(f.policy()))) {
                throw bad("unknown policy of " + f.path());
            }
        }
        for (String r : retiredOrEmpty()) {
            if (!PackPaths.isSafeRel(r)) {
                throw path(r);
            }
        }
    }

    /** A path this launcher does not know: a newer pack format, not a bad signature. */
    private static PackException path(String rel) {
        return new PackException(PackException.PATH, rel);
    }

    private static PackException bad(String message) {
        return new PackException(PackException.SIGNATURE, "the manifest is damaged: " + message);
    }
}
