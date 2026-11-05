package com.azur.launcher.ui;

import com.azur.launcher.account.LoginHandoff;
import com.azur.launcher.account.SiteErrors;
import com.azur.launcher.account.SiteException;
import com.azur.launcher.account.SiteSession;
import com.azur.launcher.auth.AuthAccount;
import com.azur.launcher.auth.OfflineAccount;
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
import com.azur.launcher.sync.PackSyncService;
import com.azur.launcher.telemetry.ErrorCode;

import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.Path;
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
    private volatile boolean cancelled;
    /** Game directory of the last start: leftover login files are removed there when the game exits. */
    private volatile Path lastGameDir;
    private final InstallProgress installProgress = new InstallProgress();
    /** The server Home shows; {@code null} = the embedded default instance. */
    private volatile ServerEntry server;
    private volatile boolean serverLoading;
    private volatile String serverProblem;

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

    /** The list is being loaded: Play must say so instead of claiming a failure. */
    void setServerLoading() {
        serverProblem = null;
        serverLoading = true;
    }

    /** The load finished: {@code problem} is the short reason when there is no server. */
    void setServer(ServerEntry value, String problem) {
        server = value;
        serverProblem = problem;
        serverLoading = false;
    }

    /** Play for the server Home shows. */
    void startDefault() {
        startFor(server);
    }

    void repairCurrent() {
        repair(server);
    }

    InstallProgress installProgress() {
        return installProgress;
    }

    private void startFor(ServerEntry server) {
        if (phase == Phase.RUNNING) {
            GameLauncher running = game;
            ctx.io.submit(running::stop);
            ctx.setStatus(I18n.tr("home.stopping"));
            return;
        }
        if (phase == Phase.PREPARING) {
            cancelled = true;
            ctx.setStatus(I18n.tr("play.cancelling"));
            return;
        }
        AuthAccount account = ctx.site == null || ctx.site.session().isEmpty()
                ? ctx.accounts.selected() : siteAccount(ctx.site.session().get());
        if (account == null) {
            error(I18n.tr("home.sign_in_needed"));
            return;
        }
        if (server == null) {
            error(serverLoading ? I18n.tr("play.list_loading")
                    : I18n.tr("play.no_server") + (serverProblem == null ? "" : "\n\n" + I18n.tr("play.no_server_reason", serverProblem)));
            return;
        }
        cancelled = false;
        setPhase(Phase.PREPARING);
        ctx.io.submit(() -> prepareAndLaunch(server, account));
    }

    /** Signed in through the site: the game nick is the account nick (exact case), the saved nicks are not used. */
    private AuthAccount siteAccount(SiteSession session) {
        if (!OfflineAccount.isValidName(session.nick())) {
            error(I18n.tr("account.err.nick_rules"));
            return null;
        }
        return OfflineAccount.of(session.nick());
    }

    /** Ticket + key files for the game; a refused device stops the launch with one polite sentence, a lost network does not. */
    private List<String> loginArgs(Instance instance) throws IOException {
        if (ctx.site == null) {
            return List.of();
        }
        try {
            LoginHandoff.Prepared prepared = new LoginHandoff(ctx.site, "")
                    .prepare(instance.gameDir(), ctx.config.isShareKeyWithGame());
            if (!prepared.notice().isEmpty()) {
                ctx.log("Login handoff: " + prepared.notice());
            }
            return prepared.jvmArgs();
        } catch (SiteException e) {
            ctx.log("Login handoff refused: " + e.code());
            if (SiteErrors.dropsSession(e)) {
                Platform.runLater(() -> ctx.accountController.sessionRefused(SiteErrors.key(e)));
            }
            throw new IOException(I18n.tr(SiteErrors.key(e)), e);
        }
    }

    private void prepareAndLaunch(ServerEntry server, AuthAccount account) {
        try {
            Instance instance = instanceFor(server);
            if (!account.launchPermitted()) {
                throw new IllegalStateException(I18n.tr("auth.launch_blocked"));
            }
            installProgress.begin(new PinService(ctx.dirs, ctx.gamePaths(), ctx::log).check(instance).size() > 0);
            PlayFlow.run(new FlowSteps(server, instance, account), step -> {
                if (cancelled) {
                    throw new java.util.concurrent.CancellationException();
                }
                ctx.log("Play step: " + step);
                ctx.setStatus(I18n.tr("play.step." + step.name().toLowerCase()));
            }, (step, ms, failure) -> ctx.telemetry.report(telemetryStep(step), failure == null, failure == null ? "" : ErrorCode.classify(failure), ms));
        } catch (java.util.concurrent.CancellationException e) {
            ctx.log("Play cancelled by the player");
            fail(I18n.tr("play.cancelled"), false);
        } catch (PlayFlow.StepFailed e) {
            ctx.log("Launch failed at " + e.step() + ": " + e.getMessage());
            fail(describe(e), true);
        } catch (Exception e) {
            ctx.log("Launch failed: " + e.getMessage());
            fail(e.getMessage(), true);
        }
    }

    /** The fixed step words the site accepts. */
    private static String telemetryStep(PlayFlow.Step step) {
        return switch (step) {
            case GAME -> "install";
            case PACK -> "download";
            case LAUNCH -> "launch";
        };
    }

    private void fail(String message, boolean dialog) {
        ctx.setProgress(-1);
        installProgress.finish();
        setPhase(Phase.IDLE);
        if (dialog) {
            Platform.runLater(() -> error(message));
        } else {
            ctx.setStatus(message);
        }
    }

    /** One readable sentence per failed step: network, maintenance, no disk space, proxy. */
    static String describe(PlayFlow.StepFailed e) {
        String raw = String.valueOf(e.getMessage());
        String lower = raw.toLowerCase();
        String reason = lower.contains("no space left") || lower.contains("not enough space")
                ? I18n.tr("play.err.disk") : raw;
        return I18n.tr("play.err." + e.step().name().toLowerCase(), reason);
    }

    /** The real work of every PlayFlow step; the pack decision uses a fresh /status probe, never the Home cache. */
    private final class FlowSteps implements PlayFlow.Steps {
        private final ServerEntry server;
        private final Instance instance;
        private final AuthAccount account;

        FlowSteps(ServerEntry server, Instance instance, AuthAccount account) {
            this.server = server;
            this.instance = instance;
            this.account = account;
        }

        @Override
        public boolean gameReady() {
            return new PinService(ctx.dirs, ctx.gamePaths(), ctx::log).check(instance).isEmpty();
        }

        @Override
        public void installGame() throws IOException {
            PackOps.installGame(ctx, instance, installProgress);
        }

        @Override
        public boolean packCurrent() {
            if (!server.pack().present()) {
                return true;
            }
            PackSyncService service = PackOps.newService(ctx);
            String local = service.installedVersion(instance.gameDir());
            PackSyncService.Remote remote = service.probe(server.pack(), java.time.Duration.ofSeconds(6));
            boolean needed = PlayFlow.packNeeded(local, remote.reachable(), remote.version());
            if (needed) {
                ctx.log("Play step: PACK (installed " + (local.isBlank() ? "unknown" : local) + " -> server "
                        + remote.version() + ")");
            } else if (!remote.reachable()) {
                ctx.log("Pack server unreachable: playing the local copy " + local);
            }
            return !needed;
        }

        @Override
        public void installPack() throws IOException {
            PackOps.Outcome outcome = PackOps.installPack(ctx, server, instance, installProgress);
            PackSyncService.Result result = outcome.result();
            if (!result.ok()) {
                String key = "sync.err." + result.code();
                throw new IOException(I18n.tr(key).equals(key) ? result.message() : I18n.tr(key));
            }
        }

        @Override
        public void launch() throws Exception {
            ctx.setStatus(I18n.tr("play.checking"));
            installProgress.finish();
            GameLauncher launcher = new GameLauncher(ctx.gamePaths(), ctx::log, progress()).withInstance(instance);
            launcher.addExitSink(code -> Platform.runLater(() -> onExit(code, launcher)));
            lastGameDir = instance.gameDir();
            launcher.withLoginArgs(() -> loginArgs(instance));
            game = launcher;
            launcher.setHangHandler((message, keyLines, dump) -> askTerminate(message, keyLines, dump, launcher));
            launcher.launch(ctx.config, account);
            ctx.setProgress(-1);
            ctx.setStatus(I18n.tr("home.running"));
            setPhase(Phase.RUNNING);
        }
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

    /** Called from the watchdog thread; shows the dialog on the FX thread and waits for the answer. */
    private boolean askTerminate(String message, List<String> keyLines, Path dump, GameLauncher launcher) {
        java.util.concurrent.CompletableFuture<Boolean> answer = new java.util.concurrent.CompletableFuture<>();
        Platform.runLater(() -> {
            Path logs = dump != null ? dump.getParent() : ctx.dirs.logsDir();
            answer.complete(UiKit.hangDialog(ctx.stage, message, String.join("\n", keyLines),
                    () -> ctx.openInSystem(logs), ctx.config.isDarkMode()));
        });
        try {
            return answer.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return true;
        }
    }

    private void onExit(int code, GameLauncher launcher) {
        setPhase(Phase.IDLE);
        Path dir = lastGameDir;
        if (dir != null) {
            ctx.io.submit(() -> LoginHandoff.cleanup(dir));
        }
        ctx.setStatus(I18n.tr("home.exited", code));
        if (code != 0) {
            Path logFile = launcher.gameLogFile();
            UiKit.gameExitDialog(ctx.stage, code, String.join("\n", launcher.lastLines()),
                    () -> ctx.openInSystem(logFile == null ? ctx.dirs.logsDir() : logFile.getParent()),
                    ctx.config.isDarkMode());
        }
    }

    private void setPhase(Phase value) {
        phase = value;
        Platform.runLater(() -> listeners.forEach(l -> l.accept(value)));
    }

    private void error(String message) {
        UiKit.errorDialog(ctx.stage, message, ctx.config.isDarkMode());
    }
}
