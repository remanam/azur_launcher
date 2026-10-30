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
import javafx.scene.control.ProgressBar;
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

/** Servers: the signed list on the left, the selected server (status, pack, Update / Play) on the right. */
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
    private final ProgressBar progress = new ProgressBar(0);
    private final Button updateButton;
    private final Button verifyButton;
    private final Button downloadButton;
    private final Button repairButton;
    private final Button playButton;

    private List<ServerEntry> servers = List.of();
    private ServerEntry selected;
    private volatile boolean busy;

    ServersPage(AppContext ctx, PlayController play) {
        this.ctx = ctx;
        this.play = play;
        this.status = new ServerStatusService(ctx.io, PING_INTERVAL_MS);
        updateButton = UiKit.button(I18n.tr("servers.update"), "icon-refresh", "button-raised", this::sync);
        verifyButton = UiKit.button(I18n.tr("servers.verify"), null, "button-border", this::verify);
        downloadButton = UiKit.button(I18n.tr("servers.download"), null, "button-border", this::downloadClient);
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
        progress.setMaxWidth(Double.MAX_VALUE);
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
                        ServerListService.loadKey(ctx.dirs.serversKeyFile()), ctx.pack.getServersUrl(),
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
        if (servers.isEmpty()) {
            Label empty = UiKit.label(I18n.tr("servers.empty"), "empty-hint");
            empty.setWrapText(true);
            listBox.getChildren().add(empty);
        }
        select(servers.stream().filter(e -> selected != null && e.id().equals(selected.id())).findFirst()
                .orElse(servers.isEmpty() ? null : servers.get(0)));
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
        rows.forEach((id, view) -> {
            view.row().getStyleClass().remove("selected");
            if (entry != null && id.equals(entry.id())) {
                view.row().getStyleClass().add("selected");
            }
        });
        message.setText("");
        progress.setVisible(false);
        progress.setManaged(false);
        renderDetail();
        if (entry != null) {
            ping(entry);
        }
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
                kv("servers.pack", packVersions.getOrDefault(e.id(), "-")),
                kv("servers.invite", I18n.tr(e.inviteRequired() ? "servers.invite_yes" : "servers.invite_no")));
        HBox actions = new HBox(10, updateButton, verifyButton, downloadButton, repairButton, UiKit.spacer(), playButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox pack = UiKit.card(UiKit.cardHeader("icon-java", I18n.tr("servers.pack_title")), actions, progress, UiKit.withCopy(message));
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

    // ------------------------------------------------------------------ pack sync

    private void sync() {
        run(false);
    }

    private void verify() {
        run(true);
    }

    /**
     * "Download client": installs the pin and fetches everything the game needs (client jar, libraries, assets) and then
     * the pack, showing progress for both stages. Repeating after an interruption continues with the missing files.
     */
    private void downloadClient() {
        ServerEntry entry = selected;
        if (entry == null || busy) {
            return;
        }
        setBusy(true);
        message.setText(I18n.tr("download.starting"));
        ctx.io.submit(() -> {
            ctx.setStatus(I18n.tr("download.starting"));
            PackOps.Outcome outcome = PackOps.download(ctx, entry,
                    (phase, file, done, total) -> Platform.runLater(() -> showProgress(phase, file, done, total)),
                    (done, total, current) -> Platform.runLater(() -> showProgress("download", current, done, total)));
            Platform.runLater(() -> finishDownload(outcome));
        });
    }

    private void finishDownload(PackOps.Outcome outcome) {
        setBusy(false);
        progress.setVisible(false);
        progress.setManaged(false);
        PackSyncService.Result result = outcome.result();
        if (result.ok()) {
            message.setText(I18n.tr("download.ready"));
            ctx.setStatus(I18n.tr("download.ready"));
            return;
        }
        String key = "sync.err." + result.code();
        String text = I18n.tr(key).equals(key) ? result.message() : I18n.tr(key);
        message.setText(I18n.tr("sync.failed", text));
        ctx.log("Download client failed: exit " + result.exit() + " " + result.code() + " " + result.message());
    }

    private void run(boolean verifyOnly) {
        ServerEntry entry = selected;
        if (entry == null || busy) {
            return;
        }
        String invite = ctx.invite();
        if (!verifyOnly && invite.isBlank()) {
            message.setText(I18n.tr("sync.no_invite"));
            return;
        }
        setBusy(true);
        message.setText(I18n.tr(verifyOnly ? "sync.verifying" : "sync.starting"));
        ctx.io.submit(() -> {
            PackOps.Outcome outcome;
            try {
                Path instance = instanceDir(entry);
                PackSyncService service = PackOps.newService(ctx);
                outcome = verifyOnly ? new PackOps.Outcome(service.verify(instance), true, false)
                        : PackOps.update(service, entry, instance, invite,
                                (phase, file, done, total) -> Platform.runLater(() -> showProgress(phase, file, done, total)));
            } catch (IOException e) {
                ctx.log("Pack sync: " + e.getMessage());
                outcome = new PackOps.Outcome(new PackSyncService.Result(-1, "unknown", e.getMessage(), ""), verifyOnly, false);
            }
            PackOps.Outcome finished = outcome;
            Platform.runLater(() -> finish(entry, finished));
        });
    }

    /** The game folder of the server's instance (profile written from the signed entry on first use). */
    private Path instanceDir(ServerEntry entry) throws IOException {
        Instance instance = new InstanceStore(ctx.dirs).ensure(ServerProfiles.derive(entry, InstanceStore.bundledProfile()));
        return instance.gameDir();
    }

    private void showProgress(String phase, String file, long done, long total) {
        progress.setVisible(true);
        progress.setManaged(true);
        progress.setProgress(total <= 0 ? ProgressBar.INDETERMINATE_PROGRESS : done / (double) total);
        message.setText(I18n.tr("sync." + phase, file));
    }

    private void finish(ServerEntry entry, PackOps.Outcome outcome) {
        setBusy(false);
        progress.setVisible(false);
        progress.setManaged(false);
        PackSyncService.Result result = outcome.result();
        if (result.ok()) {
            if (!outcome.verifyOnly() && !result.packVersion().isBlank()) {
                packVersions.put(entry.id(), result.packVersion());
                if (selected == entry) {
                    renderDetail();
                }
            }
            String done;
            if (outcome.localCopy()) {
                done = I18n.tr("sync.local_copy");
            } else if (outcome.verifyOnly()) {
                done = I18n.tr("sync.verified");
            } else {
                done = I18n.tr("sync.done", result.packVersion());
            }
            message.setText(done);
            ctx.setStatus(done);
            return;
        }
        String key = "sync.err." + result.code();
        String text = I18n.tr(key).equals(key) ? result.message() : I18n.tr(key);
        message.setText(I18n.tr("sync.failed", text));
        ctx.log("Pack sync failed: exit " + result.exit() + " " + result.code() + " " + result.message());
    }

    private void setBusy(boolean value) {
        busy = value;
        updateButton.setDisable(value);
        verifyButton.setDisable(value);
    }

    /** Play turns into Stop while the game runs; Update and Repair wait until the game is closed. */
    private void showPhase(PlayController.Phase phase) {
        boolean idle = phase == PlayController.Phase.IDLE;
        repairButton.setDisable(!idle);
        updateButton.setDisable(!idle || busy);
        downloadButton.setDisable(!idle || busy);
        playButton.setDisable(phase == PlayController.Phase.PREPARING);
        playButton.setText(I18n.tr(phase == PlayController.Phase.RUNNING ? "home.stop" : "home.play"));
        playButton.getStyleClass().remove("stopping");
        if (phase == PlayController.Phase.RUNNING) {
            playButton.getStyleClass().add("stopping");
        }
    }
}
