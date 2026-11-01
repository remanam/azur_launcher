package com.azur.launcher.ui;

import com.azur.launcher.i18n.I18n;
import com.azur.launcher.launch.LaunchRequest;
import com.azur.launcher.net.HttpUtils;
import com.azur.launcher.profile.Instance;
import com.azur.launcher.profile.InstanceStore;
import com.azur.launcher.profile.ServerProfiles;
import com.azur.launcher.servers.ServerEntry;
import com.azur.launcher.servers.ServerListService;
import com.azur.launcher.servers.ServerPing;
import com.azur.launcher.servers.ServerStatusService;
import com.azur.launcher.sync.PackSyncService;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Servers: the signed list on the left, the selected server (status, pack info, Play) on the right. */
final class ServersPage {

    private static final long PING_INTERVAL_MS = 15_000;
    private static final double LIST_WIDTH = 270;

    /** Row widgets that change after a ping. */
    private record RowView(HBox row, Region dot, Label info) {
    }

    private final AppContext ctx;
    private final PlayController play;
    private final ServerStatusService status;
    private final HBox root = new HBox(16);
    private final VBox listBox = new VBox(8);
    private final VBox detail = new VBox(12);
    private final Label banner = UiKit.label("", "banner-warn");
    private final Map<String, RowView> rows = new HashMap<>();
    private final Map<String, String> packVersions = new HashMap<>();
    private final SelectableText message = UiKit.selectable("", "card-subtitle");
    private final Button repairButton;
    private final Button playButton;

    private List<ServerEntry> servers = List.of();
    private ServerEntry selected;

    ServersPage(AppContext ctx, PlayController play) {
        this.ctx = ctx;
        this.play = play;
        this.status = new ServerStatusService(ctx.io, PING_INTERVAL_MS);
        repairButton = UiKit.button(I18n.tr("servers.repair"), null, "button-border", () -> {
            if (selected != null) {
                play.repair(selected);
            }
        });
        playButton = UiKit.button(I18n.tr("home.play"), "icon-play", "launch-button", () -> {
            if (selected != null) {
                play.start(new LaunchRequest(selected));
            }
        });
        playButton.getStyleClass().add("compact");
        play.onPhase(this::showPhase);

        Button reload = UiKit.button(I18n.tr("home.refresh"), "icon-refresh", "button-border", this::reload);
        banner.setWrapText(true);
        banner.setVisible(false);
        banner.setManaged(false);
        VBox left = new VBox(10, UiKit.label(I18n.tr("servers.title"), "page-title"),
                UiKit.label(I18n.tr("servers.subtitle"), "page-subtitle"), banner, listBox, reload);
        left.setMinWidth(LIST_WIDTH);
        left.setPrefWidth(LIST_WIDTH);
        left.setMaxWidth(LIST_WIDTH);
        HBox.setHgrow(detail, Priority.ALWAYS);
                root.getStyleClass().add("page");
        root.getChildren().addAll(left, detail);
        renderDetail();
        reload();
    }

    Node node() {
        return UiKit.pageScroll(root);
    }

    // ------------------------------------------------------------------ list

    private void reload() {
        ctx.io.submit(() -> {
            try {
                ServerListService service = new ServerListService(ctx.dirs.serversCacheFile(),
                        ServerListService.appKey(ctx.dirs.serversKeyFile()), ctx.pack.getServersUrl(),
                        HttpUtils::getBytes, Clock.systemUTC(), ctx::log);
                ServerListService.Loaded loaded = service.load();
                Platform.runLater(() -> showList(loaded));
            } catch (IOException | java.security.GeneralSecurityException e) {
                ctx.log("Server list: " + e.getMessage());
                Platform.runLater(() -> showListError(e));
            }
        });
    }

    private void showList(ServerListService.Loaded loaded) {
        servers = loaded.list().servers();
        status.reset();
        rows.clear();
        listBox.getChildren().clear();
        String bannerKey = switch (loaded.source()) {
            case NETWORK -> null;
            case CACHE -> "servers.from_cache";
            case CACHE_STALE -> "servers.stale";
        };
        banner.setVisible(bannerKey != null);
        banner.setManaged(bannerKey != null);
        banner.setText(bannerKey == null ? "" : I18n.tr(bannerKey));
        for (ServerEntry entry : servers) {
            listBox.getChildren().add(row(entry));
        }
        if (!com.azur.launcher.servers.ServerChoice.selectorVisible(servers)) {
            listBox.getChildren().clear();
        }
        if (servers.isEmpty()) {
            Label empty = UiKit.label(I18n.tr("servers.empty"), "empty-hint");
            empty.setWrapText(true);
            listBox.getChildren().add(empty);
        }
        select(com.azur.launcher.servers.ServerChoice.pick(servers,
                selected != null ? selected.id() : ctx.config.getLastServerId()));
    }

    private void showListError(Exception e) {
        servers = List.of();
        rows.clear();
        String reason = ctx.pack.getServersUrl().isBlank() && !Files.isRegularFile(ctx.dirs.serversCacheFile())
                ? I18n.tr("servers.err.no_source", ctx.dirs.serversCacheFile())
                : e.getMessage();
        Label text = UiKit.label(I18n.tr("servers.unavailable", reason), "empty-hint");
        text.setWrapText(true);
        listBox.getChildren().setAll(text);
        select(null);
    }

    private HBox row(ServerEntry entry) {
        Region dot = new Region();
        dot.getStyleClass().add("status-dot");
        Label info = UiKit.label(I18n.tr("servers.checking"), "card-subtitle");
        VBox text = new VBox(2, UiKit.label(entry.name(), "server-name"), info);
        HBox row = new HBox(10, dot, text);
        row.getStyleClass().add("server-row");
        row.setOnMouseClicked(event -> select(entry));
        rows.put(entry.id(), new RowView(row, dot, info));
        ping(entry);
        return row;
    }

    private void ping(ServerEntry entry) {
        status.request(entry, result -> Platform.runLater(() -> showPing(entry, result)));
    }

    private void showPing(ServerEntry entry, ServerPing.Result result) {
        RowView view = rows.get(entry.id());
        if (view == null) {
            return;
        }
        view.dot().getStyleClass().removeAll("online", "down");
        view.dot().getStyleClass().add(result.online() ? "online" : "down");
        view.info().setText(result.online()
                ? I18n.tr("servers.online", result.playersOnline(), result.playersMax(), result.latencyMs())
                : I18n.tr("servers.down"));
    }

    private void select(ServerEntry entry) {
        selected = entry;
        if (entry != null && !entry.id().equals(ctx.config.getLastServerId())) {
            ctx.config.setLastServerId(entry.id());
            ctx.saveConfig();
        }
        rows.forEach((id, view) -> {
            view.row().getStyleClass().remove("selected");
            if (entry != null && id.equals(entry.id())) {
                view.row().getStyleClass().add("selected");
            }
        });
        message.setText("");
        renderDetail();
        if (entry != null) {
            ping(entry);
            loadPackInfo(entry);
        }
    }

    /** The game folder of the server's instance (profile written from the signed entry on first use). */
    private Path instanceDir(ServerEntry entry) throws IOException {
        return new InstanceStore(ctx.dirs).ensure(ServerProfiles.derive(entry, InstanceStore.bundledProfile())).gameDir();
    }

    /** Installed and announced pack versions (cheap /status probe), shown as information only. */
    private void loadPackInfo(ServerEntry entry) {
        if (!entry.pack().present()) {
            return;
        }
        ctx.io.submit(() -> {
            try {
                PackSyncService service = PackOps.newService(ctx);
                String local = service.installedVersion(instanceDir(entry));
                String remote = service.probe(entry.pack()).version();
                Platform.runLater(() -> {
                    packVersions.put(entry.id(), local.isBlank() ? "-" : local);
                    if (selected == entry) {
                        message.setText(I18n.tr("servers.pack_info", local.isBlank() ? "-" : local,
                                remote.isBlank() ? "-" : remote));
                        renderDetail();
                    }
                });
            } catch (IOException e) {
                ctx.log("Pack info: " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ detail

    private void renderDetail() {
        detail.getChildren().clear();
        if (selected == null) {
            detail.getChildren().add(UiKit.card(UiKit.label(I18n.tr("servers.select"), "empty-hint")));
            return;
        }
        ServerEntry e = selected;
        Label motd = UiKit.label(e.motd(), "card-subtitle");
        motd.setWrapText(true);
        VBox info = UiKit.card(UiKit.cardHeader("icon-versions", e.name()), motd,
                kv("servers.address", e.hostPort()),
                kv("servers.mc", e.mcVersion().isBlank() ? "-" : e.mcVersion()),
                kv("servers.neoforge", e.neoforge().isBlank() ? "-" : e.neoforge()),
                kv("servers.java", e.java().isBlank() ? "-" : "Java " + e.java()),
                kv("servers.channel", e.packChannel().isBlank() ? "-" : e.packChannel()),
                kv("servers.pack", packVersions.getOrDefault(e.id(), "-")));
        HBox actions = new HBox(10, repairButton, UiKit.spacer(), playButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox pack = UiKit.card(UiKit.cardHeader("icon-java", I18n.tr("servers.pack_title")), actions, UiKit.withCopy(message));
        VBox news = UiKit.card(UiKit.cardHeader("icon-terminal", I18n.tr("servers.news")),
                UiKit.label(I18n.tr("servers.news_placeholder"), "card-subtitle"));
        detail.getChildren().addAll(info, pack, news);
    }

    private static HBox kv(String key, String value) {
        Label v = UiKit.label(value, "kv-value");
        v.setWrapText(true);
        HBox row = new HBox(8, UiKit.label(I18n.tr(key), "kv-key"), v);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Play installs and updates what is missing, then starts; it turns into Cancel while preparing and Stop while the game runs. */
    private void showPhase(PlayController.Phase phase) {
        boolean idle = phase == PlayController.Phase.IDLE;
        repairButton.setDisable(!idle);
        playButton.setText(I18n.tr(phase == PlayController.Phase.RUNNING ? "home.stop"
                : phase == PlayController.Phase.PREPARING ? "home.cancel" : "home.play"));
        playButton.getStyleClass().remove("stopping");
        if (phase == PlayController.Phase.RUNNING) {
            playButton.getStyleClass().add("stopping");
        }
    }
}
