package com.azur.launcher.ui;

import com.azur.launcher.feed.FeedDocument;
import com.azur.launcher.feed.FeedFilter;
import com.azur.launcher.feed.FeedNews;
import com.azur.launcher.feed.FeedNotice;
import com.azur.launcher.feed.FeedState;
import com.azur.launcher.feed.FeedView;
import com.azur.launcher.i18n.I18n;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The notice feed on the Play screen: a bell with the unread count and history (top right), a banner with the most
 * important open notice, and one line with the latest news. All rules live in {@link FeedView}; this class only draws.
 */
final class FeedPanel {

    private static final int MAX_WIDTH = 520;

    private final AppContext ctx;
    private final FeedState state;
    private final Supplier<String> serverId;
    private final Runnable onChange;
    private final Button bell = UiKit.button("", null, "feed-bell", this::toggleHistory);
    private final Label bellCount = UiKit.label("", "feed-count");
    private final VBox bannerBox = new VBox(6);
    private final Label bannerTitle = UiKit.label("", "feed-title");
    private final Label bannerText = UiKit.label("", "feed-text");
    private final Button bannerLink = UiKit.button(I18n.tr("feed.more"), null, "feed-link", () -> { });
    private final Button bannerClose = UiKit.button("✕", null, "feed-close", this::closeBanner);
    private final VBox history = new VBox(8);
    private final Label newsBadge = UiKit.label(I18n.tr("feed.badge"), "feed-badge");
    private final Label newsLine = UiKit.label("", "feed-news");
    private final Button newsLink = UiKit.button("", null, "feed-link", () -> { });
    private final HBox newsRow = new HBox(12, newsBadge, newsLine, UiKit.spacer(), newsLink);
    private FeedDocument doc;
    private FeedView view = FeedView.EMPTY;
    private boolean historyOpen;

    FeedPanel(AppContext ctx, FeedState state, Supplier<String> serverId, Runnable onChange) {
        this.ctx = ctx;
        this.state = state;
        this.serverId = serverId;
        this.onChange = onChange;
        bell.setGraphic(new HBox(6, Icons.of("icon-bell", 18), bellCount));
        bell.setTooltip(new javafx.scene.control.Tooltip(I18n.tr("feed.bell")));
        HBox head = new HBox(8, bannerTitle, UiKit.spacer(), bannerClose);
        head.setAlignment(Pos.CENTER_LEFT);
        bannerText.setWrapText(true);
        bannerBox.getChildren().addAll(head, bannerText, bannerLink);
        history.getStyleClass().add("feed-history");
        history.setMaxWidth(MAX_WIDTH);
        history.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        newsRow.getStyleClass().add("feed-news-row");
        newsRow.setAlignment(Pos.CENTER_LEFT);
        render();
    }

    /** Bell with the unread count (hidden while the feed is empty). */
    Node bell() {
        return bell;
    }

    /** The banner with the most important open notice (hidden when none). */
    Node banner() {
        return bannerBox;
    }

    /** History list, shown as a popup while the bell is open. */
    Node history() {
        return history;
    }

    /** The one-line news strip at the bottom of the window. */
    Node news() {
        return newsRow;
    }

    /** True while an active critical notice blocks the Play button (same effect as a required launcher update). */
    boolean blocksPlay() {
        return view.blocksPlay();
    }

    /** JavaFX thread: a new or changed feed, or another server was picked. */
    void show(FeedDocument value) {
        doc = value;
        refresh();
    }

    void refresh() {
        FeedFilter.Context context = new FeedFilter.Context(Instant.now(), com.azur.launcher.AppVersion.APP,
                FeedFilter.osOf(System.getProperty("os.name")), Objects.isNull(serverId.get()) ? "" : serverId.get());
        view = FeedView.of(doc, context, state);
        render();
        onChange.run();
    }

    private void toggleHistory() {
        historyOpen = !historyOpen;
        if (historyOpen) {
            state.markRead(view.active());
            refresh();
        } else {
            render();
        }
    }

    private void closeBanner() {
        view.banner().ifPresent(state::close);
        refresh();
    }

    private void render() {
        String lang = I18n.language();
        bellCount.setText(view.unread() > 0 ? String.valueOf(view.unread()) : "");
        bellCount.setVisible(view.unread() > 0);
        bellCount.setManaged(view.unread() > 0);
        bell.setVisible(!view.active().isEmpty());
        bell.setManaged(bell.isVisible());
        FeedNotice banner = view.banner().orElse(null);
        bannerBox.setVisible(Objects.nonNull(banner));
        bannerBox.setManaged(Objects.nonNull(banner));
        if (Objects.nonNull(banner)) {
            bannerTitle.setText(banner.title().in(lang));
            bannerText.setText(banner.text().in(lang));
            bannerBox.getStyleClass().removeIf(c -> c.startsWith("feed-sev-") || c.equals("feed-banner"));
            bannerBox.getStyleClass().addAll("feed-banner", "feed-sev-" + banner.severity().wire());
            bannerClose.setVisible(banner.severity() != FeedNotice.Severity.CRITICAL);
            bannerClose.setManaged(bannerClose.isVisible());
            linkButton(bannerLink, banner.link(), I18n.tr("feed.more"));
        }
        history.getChildren().clear();
        if (historyOpen) {
            for (FeedNotice n : view.active()) {
                Label row = UiKit.label(n.title().in(lang) + ": " + n.text().in(lang), "feed-history-row");
                row.setWrapText(true);
                row.setMaxWidth(MAX_WIDTH);
                history.getChildren().add(row);
            }
        }
        history.setVisible(historyOpen);
        history.setManaged(historyOpen);
        FeedNews news = view.latestNews().orElse(null);
        newsRow.setVisible(Objects.nonNull(news));
        newsRow.setManaged(Objects.nonNull(news));
        if (Objects.nonNull(news)) {
            newsLine.setText(I18n.tr("feed.news", news.date(), news.title().in(lang)));
            linkButton(newsLink, news.link(), I18n.tr("feed.read"));
        }
    }

    private void linkButton(Button button, String link, String text) {
        boolean ok = link.startsWith("https://");
        button.setText(text);
        button.setVisible(ok);
        button.setManaged(ok);
        button.setOnAction(event -> ctx.host.showDocument(link));
    }
}
