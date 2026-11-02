package com.azur.launcher.feed;

import com.azur.launcher.update.LauncherVersions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Which notices apply here and now (pure, tested), and which one is the most important. */
public final class FeedFilter {

    /** Where the launcher runs. {@code serverId} may be empty (no server picked yet). */
    public record Context(Instant now, String launcherVersion, String os, String serverId) {
    }

    private FeedFilter() {
    }

    /** "windows", "mac", "linux" or "other" from {@code os.name}. */
    public static String osOf(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return "windows";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "mac";
        }
        return name.contains("linux") || name.contains("nux") ? "linux" : "other";
    }

    public static boolean applies(FeedNotice n, Context ctx) {
        if (!n.account().isEmpty()) {
            return false; // personal notices belong to stage 3
        }
        Optional<Instant> start = time(n.starts());
        Optional<Instant> end = time(n.expires());
        if (!n.starts().isEmpty() && (start.isEmpty() || ctx.now().isBefore(start.get()))) {
            return false;
        }
        if (!n.expires().isEmpty() && (end.isEmpty() || !ctx.now().isBefore(end.get()))) {
            return false;
        }
        if (!n.minLauncher().isEmpty() && LauncherVersions.compare(ctx.launcherVersion(), n.minLauncher()) < 0) {
            return false;
        }
        if (!n.maxLauncher().isEmpty() && LauncherVersions.compare(ctx.launcherVersion(), n.maxLauncher()) > 0) {
            return false;
        }
        if (!n.os().isEmpty() && !n.os().contains(ctx.os())) {
            return false;
        }
        return n.serverIds().isEmpty() || n.serverIds().contains(ctx.serverId());
    }

    /** Active notices, most important first (critical, warn, info; newer start first). */
    public static List<FeedNotice> active(List<FeedNotice> all, Context ctx) {
        return all.stream().filter(n -> applies(n, ctx))
                .sorted(Comparator.comparing((FeedNotice n) -> n.severity().ordinal()).reversed()
                        .thenComparing(n -> time(n.starts()).orElse(Instant.EPOCH), Comparator.reverseOrder()))
                .toList();
    }

    /** RFC 3339 or YYYY-MM-DD (UTC midnight); empty for anything else. */
    static Optional<Instant> time(String value) {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException ignored) {
            // fall through to a plain date
        }
        try {
            return Optional.of(LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
