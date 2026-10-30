package com.azur.launcher.ui;

import com.azur.launcher.auth.AuthAccount;
import com.azur.launcher.auth.AuthException;
import com.azur.launcher.auth.AuthNetwork;
import com.azur.launcher.auth.MicrosoftAccount;
import com.azur.launcher.auth.MicrosoftAuthService;
import com.azur.launcher.auth.SessionManager;
import com.azur.launcher.game.GameLauncher;
import com.azur.launcher.game.PinService;
import com.azur.launcher.i18n.I18n;
import com.azur.launcher.launch.GameStarter;
import com.azur.launcher.launch.LaunchRequest;
import com.azur.launcher.net.DownloadManager;
import com.azur.launcher.profile.Instance;
import com.azur.launcher.profile.InstanceStore;
import com.azur.launcher.profile.ServerProfiles;
import com.azur.launcher.servers.ServerEntry;

import javafx.application.Platform;
import javafx.scene.control.Alert;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The one place that starts the game: server entry to per-server instance (pin from the signed list), verified
 * account, pinned install, launch. Also the "verify files" repair. Only one game process at a time.
 */
final class PlayController implements GameStarter {

    enum Phase { IDLE, PREPARING, RUNNING }

    private final AppContext ctx;
    private final List<Consumer<Phase>> listeners = new ArrayList<>();
    private volatile Phase phase = Phase.IDLE;
    private GameLauncher game;
    /** The server Home shows; {@code null} = the embedded default instance. */
    private volatile ServerEntry server;

    PlayController(AppContext ctx) {
        this.ctx = ctx;
    }

    Phase phase() {
        return phase;
    }

    /** Called on the JavaFX thread whenever the phase changes. */
    void onPhase(Consumer<Phase> listener) {
        listeners.add(listener);
    }

    /** Play for the server of the request ({@code null} server: the embedded default instance); stops a running game. */
    @Override
    public void start(LaunchRequest request) {
        startFor(request == null ? null : request.server());
    }

    void setServer(ServerEntry value) {
        server = value;
    }

    /** Play for the server Home shows. */
    void startDefault() {
        startFor(server);
    }

    void repairCurrent() {
        repair(server);
    }

    private void startFor(ServerEntry server) {
        if (phase == Phase.RUNNING) {
            GameLauncher running = game;
            ctx.io.submit(running::stop);
            ctx.setStatus(I18n.tr("home.stopping"));
            return;
        }
        if (phase == Phase.PREPARING) {
            return;
        }
        AuthAccount account = ctx.accounts.selected();
        if (account == null) {
            error(I18n.tr("home.sign_in_needed"));
            return;
        }
        setPhase(Phase.PREPARING);
        ctx.io.submit(() -> {
            try {
                Instance instance = instanceFor(server);
                refreshAccountIfNeeded(account);
                if (!account.launchPermitted()) {
                    throw new IllegalStateException(I18n.tr("auth.launch_blocked"));
                }
                ctx.setStatus(I18n.tr("play.checking"));
                new PinService(ctx.dirs, ctx.gamePaths(), ctx::log).installIfNeeded(instance);
                GameLauncher launcher = new GameLauncher(ctx.gamePaths(), ctx::log, progress()).withInstance(instance);
                launcher.addExitSink(code -> Platform.runLater(() -> onExit(code)));
                game = launcher;
                launcher.launch(ctx.config, account);
                ctx.setProgress(-1);
                ctx.setStatus(I18n.tr("home.running"));
                setPhase(Phase.RUNNING);
            } catch (Exception e) {
                ctx.log("Launch failed: " + e.getMessage());
                ctx.setProgress(-1);
                setPhase(Phase.IDLE);
                Platform.runLater(() -> error(e.getMessage()));
            }
        });
    }

    /** "Verify files": reinstall on a pin mismatch, re-hash libraries, assets and the client jar. */
    void repair(ServerEntry server) {
        if (phase != Phase.IDLE) {
            return;
        }
        setPhase(Phase.PREPARING);
        ctx.setStatus(I18n.tr("play.repairing"));
        ctx.io.submit(() -> {
            try {
                Instance instance = instanceFor(server);
                new PinService(ctx.dirs, ctx.gamePaths(), ctx::log)
                        .repair(instance, ctx.config.getDownloadThreads(), progress());
                ctx.setStatus(I18n.tr("play.repaired"));
            } catch (Exception e) {
                ctx.log("Repair failed: " + e.getMessage());
                ctx.setStatus(I18n.tr("play.repair_failed"));
                Platform.runLater(() -> error(e.getMessage()));
            } finally {
                ctx.setProgress(-1);
                setPhase(Phase.IDLE);
            }
        });
    }

    private Instance instanceFor(ServerEntry server) throws IOException {
        InstanceStore store = new InstanceStore(ctx.dirs);
        if (server == null) {
            return store.loadDefault();
        }
        return store.ensure(ServerProfiles.derive(server, InstanceStore.bundledProfile()));
    }

    private DownloadManager.ProgressListener progress() {
        return (done, total, current) -> ctx.setProgress(total == 0 ? -1 : done / (double) total);
    }

    /** Verifies/refreshes a Microsoft session; a still-valid cached one is used when the auth hosts are unreachable. */
    private void refreshAccountIfNeeded(AuthAccount account) throws AuthException {
        if (!(account instanceof MicrosoftAccount microsoft)) {
            return;
        }
        SessionManager.State state = SessionManager.prepare(microsoft,
                new MicrosoftAuthService(AuthNetwork.quick(ctx.authSettings)), ctx.authSettings.clientId(),
                ctx::setStatus);
        if (state == SessionManager.State.CACHED_UNREACHABLE) {
            ctx.setStatus(I18n.tr("auth.using_cached_session"));
            ctx.log("Auth hosts unreachable, launching with the cached session");
        }
        if (state != SessionManager.State.VALID) {
            try {
                ctx.accounts.save();
            } catch (Exception e) {
                ctx.log("Cannot save accounts: " + e.getMessage());
            }
        }
    }

    private void onExit(int code) {
        setPhase(Phase.IDLE);
        ctx.setStatus(I18n.tr("home.exited", code));
    }

    private void setPhase(Phase value) {
        phase = value;
        Platform.runLater(() -> listeners.forEach(l -> l.accept(value)));
    }

    private void error(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(ctx.stage);
        alert.setTitle(I18n.tr("common.error"));
        alert.setHeaderText(I18n.tr("common.error"));
        alert.setContentText(message);
        UiKit.styleDialog(alert.getDialogPane(), ctx.config.isDarkMode());
        alert.showAndWait();
    }
}
