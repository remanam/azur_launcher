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
    private final VBox box = new VBox(8);
    private final Button bell = UiKit.button("", null, "feed-bell", this::toggleHistory);
    private final VBox bannerBox = new VBox(6);
    private final Label bannerTitle = UiKit.label("", "feed-title");
    private final Label bannerText = UiKit.label("", "feed-text");
    private final Button bannerLink = UiKit.button(I18n.tr("feed.more"), null, "feed-link", () -> { });
    private final Button bannerClose = UiKit.button("✕", null, "feed-close", this::closeBanner);
    private final VBox history = new VBox(6);
    private final Label newsLine = UiKit.label("", "feed-news");
    private final Button newsLink = UiKit.button("", null, "feed-link", () -> { });
    private final HBox newsRow = new HBox(8, newsLine, newsLink);
    private FeedDocument doc;
    private FeedView view = FeedView.EMPTY;
    private boolean historyOpen;

    FeedPanel(AppContext ctx, FeedState state, Supplier<String> serverId, Runnable onChange) {
        this.ctx = ctx;
        this.state = state;
        this.serverId = serverId;
        this.onChange = onChange;
        HBox top = new HBox(UiKit.spacer(), bell);
        top.setAlignment(Pos.CENTER_RIGHT);
        top.setMaxWidth(MAX_WIDTH);
        HBox head = new HBox(8, bannerTitle, UiKit.spacer(), bannerClose);
        head.setAlignment(Pos.CENTER_LEFT);
        bannerText.setWrapText(true);
        bannerBox.getChildren().addAll(head, bannerText, bannerLink);
        bannerBox.setMaxWidth(MAX_WIDTH);
        history.setMaxWidth(MAX_WIDTH);
        newsRow.setAlignment(Pos.CENTER);
        box.setAlignment(Pos.TOP_CENTER);
        box.getChildren().addAll(top, bannerBox, history, newsRow);
        render();
    }

    Node node() {
        return box;
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
                FeedFilter.osOf(System.getProperty("os.name")), serverId.get() == null ? "" : serverId.get());
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
        bell.setText(view.unread() > 0 ? "🔔 " + view.unread() : "🔔");
        bell.setVisible(!view.active().isEmpty());
        bell.setManaged(bell.isVisible());
        FeedNotice banner = view.banner().orElse(null);
        bannerBox.setVisible(banner != null);
        bannerBox.setManaged(banner != null);
        if (banner != null) {
            bannerTitle.setText(banner.title().in(lang));
            bannerText.setText(banner.text().in(lang));
            bannerBox.getStyleClass().removeIf(c -> c.startsWith("feed-sev-"));
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
        history.setManaged(historyOpen);
        FeedNews news = view.latestNews().orElse(null);
        newsRow.setVisible(news != null);
        newsRow.setManaged(news != null);
        if (news != null) {
            newsLine.setText(I18n.tr("feed.news", news.title().in(lang), news.date()));
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
