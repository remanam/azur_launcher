package com.azur.launcher.feed;

import java.util.List;
import java.util.Optional;

/**
 * What the Home screen shows for one feed and one {@link FeedState}: the banner (most important active notice that was
 * not closed), the bell list (all active notices), the unread count, whether Play is blocked, the latest news.
 */
public record FeedView(List<FeedNotice> active, Optional<FeedNotice> banner, long unread, boolean blocksPlay,
                       Optional<FeedNews> latestNews) {

    public static final FeedView EMPTY = new FeedView(List.of(), Optional.empty(), 0, false, Optional.empty());

    public static FeedView of(FeedDocument doc, FeedFilter.Context ctx, FeedState state) {
        if (doc == null) {
            return EMPTY;
        }
        List<FeedNotice> active = FeedFilter.active(doc.notices(), ctx);
        Optional<FeedNotice> banner = active.stream().filter(n -> !state.isClosed(n)).findFirst();
        long unread = active.stream().filter(n -> !state.isRead(n.id())).count();
        boolean blocks = active.stream().anyMatch(n -> n.severity() == FeedNotice.Severity.CRITICAL);
        Optional<FeedNews> news = doc.news().stream().max(java.util.Comparator.comparing(FeedNews::date));
        return new FeedView(active, banner, unread, blocks, news);
    }
}
