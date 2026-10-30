package com.azur.launcher.pack;

import com.azur.launcher.net.Hashes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a sync would do to an instance. Never touches {@code user_owned} paths, files the pack does not know, or
 * (policy {@code keep-if-exists}) configs the player already has.
 */
public record PackPlan(List<Item> items, List<String> extra, long bytes) {

    public enum Action { ADD, UPDATE, REMOVE, MERGE, KEEP, SKIP }

    public record Item(String path, Action action, long size, PackManifest.Entry file) {
        boolean needsDownload() {
            return action == Action.ADD || action == Action.UPDATE || action == Action.MERGE;
        }
    }

    public boolean hasChanges() {
        return items.stream().anyMatch(i -> i.action() == Action.ADD || i.action() == Action.UPDATE
                || i.action() == Action.REMOVE || i.action() == Action.MERGE);
    }

    public static PackPlan build(PackManifest m, Path game, PackState state) throws IOException {
        List<Item> items = new ArrayList<>();
        Set<String> inManifest = new HashSet<>();
        long bytes = 0;
        for (PackManifest.Entry f : m.filesOrEmpty()) {
            inManifest.add(f.path());
            Action action;
            if (PackPaths.matchAny(m.userOwnedOrEmpty(), f.path())
                    || (f.optional() && state.skipOptional.contains(f.path()))) {
                action = Action.SKIP;
            } else {
                Path dst = PackPaths.safeJoin(game, f.path());
                if (!Files.exists(dst)) {
                    action = Action.ADD;
                } else if (PackManifest.KEEP.equals(f.policy())) {
                    action = Action.KEEP;
                } else if (PackManifest.MERGE.equals(f.policy())) {
                    action = Action.MERGE;
                } else if (Files.size(dst) == f.size() && f.sha256().equals(Hashes.sha256(dst))) {
                    action = Action.KEEP;
                } else {
                    action = Action.UPDATE;
                }
            }
            Item item = new Item(f.path(), action, f.size(), f);
            if (item.needsDownload()) {
                bytes += f.size();
            }
            items.add(item);
        }
        Set<String> removal = new TreeSet<>(m.retiredOrEmpty());
        removal.addAll(state.owned.keySet());
        for (String r : removal) {
            if (inManifest.contains(r) || PackPaths.matchAny(m.userOwnedOrEmpty(), r) || !PackPaths.isSafeRel(r)) {
                continue;
            }
            if (Files.exists(PackPaths.safeJoin(game, r), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                items.add(new Item(r, Action.REMOVE, 0, null));
            }
        }
        items.sort(Comparator.comparing(Item::path));
        return new PackPlan(items, extraMods(m, game, state, inManifest), bytes);
    }

    /** Mods in the folder that the pack does not know: left alone, reported. */
    private static List<String> extraMods(PackManifest m, Path game, PackState state, Set<String> inManifest)
            throws IOException {
        List<String> extra = new ArrayList<>();
        Path mods = game.resolve("mods");
        if (Files.isDirectory(mods)) {
            try (var stream = Files.list(mods)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    String rel = "mods/" + p.getFileName();
                    if (!inManifest.contains(rel) && !state.owned.containsKey(rel)
                            && !m.retiredOrEmpty().contains(rel)) {
                        extra.add(rel);
                    }
                });
            }
        }
        extra.sort(String::compareTo);
        return extra;
    }
}
