package com.azur.launcher.ui;

import com.azur.launcher.AppVersion;
import com.azur.launcher.Launcher;
import com.azur.launcher.net.Checks;
import com.azur.launcher.net.HttpUtils;
import com.azur.launcher.keys.KeysetService;
import com.azur.launcher.update.Restarter;
import com.azur.launcher.update.UpdateService;
import com.azur.launcher.update.UpdateStore;

import javafx.application.Platform;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Runs the launcher self-update check at start and every {@link UpdateService#CHECK_PERIOD_MINUTES} minutes, and restarts into a staged update. */
final class UpdateController {

    private static final long HALT_AFTER_MILLIS = 2500;

    private final AppContext ctx;
    private final UpdateService service;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "azur-update");
        thread.setDaemon(true);
        return thread;
    });
    private volatile UpdateService.Status last = new UpdateService.Status(UpdateService.Kind.UP_TO_DATE, null, "");
    private volatile UpdateService.Status lastAny;
    private volatile long lastCheckMillis;
    private Consumer<UpdateService.Status> listener = status -> { };
    private Runnable panelListener = () -> { };

    private UpdateController(AppContext ctx, UpdateService service) {
        this.ctx = ctx;
        this.service = service;
    }

    /** Null when no manifest address or key is available (dev run without a servers URL). */
    static UpdateController create(AppContext ctx, UpdateStore store, BooleanSupplier gameBusy) {
        String serversUrl = ctx.pack.getServersUrl();
        if (serversUrl == null || serversUrl.isBlank()) {
            return null;
        }
        try {
            String wrapper = System.getProperty(Launcher.WRAPPER_PROPERTY, AppVersion.WRAPPER);
            UpdateService service = new UpdateService(store,
                    UpdateService.forTrust(() -> ctx.keysets.refreshIfStale(KeysetService.MAX_AGE), java.time.Clock.systemUTC()),
                    URI.create(serversUrl).resolve("/dl/launcher.json").toString(), new HttpTransport(),
                    AppVersion.APP, wrapper, gameBusy, ctx::log);
            return new UpdateController(ctx, service);
        } catch (Exception e) {
            ctx.log("Launcher update disabled: " + e.getMessage());
            return null;
        }
    }

    /** Called on the JavaFX thread after every check. */
    void onStatus(Consumer<UpdateService.Status> value) {
        listener = value;
        value.accept(last);
    }

    /** The Settings block redraws after every check, including failed and busy ones (JavaFX thread). */
    void onCheck(Runnable value) {
        panelListener = value;
    }

    /** What the Settings block shows now. */
    UpdatePanelState panelState(boolean gameBusy) {
        return UpdatePanelState.of(AppVersion.APP, lastAny, lastCheckMillis,
                Restarter.isTranslocated(System.getProperty("jpackage.app-path", "")), gameBusy);
    }

    /** "Check now" button: one check on the update thread, result through the listeners. */
    void checkAsync() {
        timer.execute(this::checkNow);
    }

    void start() {
        timer.scheduleWithFixedDelay(this::checkNow, UpdateService.FIRST_CHECK_SECONDS,
                UpdateService.CHECK_PERIOD_MINUTES * 60, TimeUnit.SECONDS);
    }

    private void checkNow() {
        UpdateService.Status status = service.check();
        if (status.kind() != UpdateService.Kind.BUSY) {
            lastAny = status;
            lastCheckMillis = System.currentTimeMillis();
        }
        if (status.kind() != UpdateService.Kind.BUSY && status.kind() != UpdateService.Kind.FAILED) {
            last = status;
            Platform.runLater(() -> listener.accept(status));
        }
        Platform.runLater(panelListener);
    }

    /**
     * "Restart now": starts a new copy (the bootstrap applies the staged jar) and closes this one for sure (a watchdog
     * halts the JVM if the shutdown hangs). The game must not run: GAME_BUSY changes nothing.
     */
    UpdateNotice.Restart restart(Runnable exit) {
        if (!service.mayRestart()) {
            return UpdateNotice.Restart.GAME_BUSY;
        }
        if (!Restarter.relaunch()) {
            ctx.log("Launcher update: cannot restart automatically, the player must reopen the launcher");
            return UpdateNotice.Restart.FAILED;
        }
        exit.run();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(HALT_AFTER_MILLIS);
            } catch (InterruptedException ignored) {
                // halts anyway
            }
            Runtime.getRuntime().halt(0);
        }, "azur-restart-halt");
        watchdog.setDaemon(true);
        watchdog.start();
        return UpdateNotice.Restart.STARTED;
    }

    void stop() {
        timer.shutdownNow();
    }

    private static final class HttpTransport implements UpdateService.Transport {
        @Override
        public byte[] get(String url) throws java.io.IOException {
            return HttpUtils.getListBytes(url);
        }

        @Override
        public void download(String url, Path target, String sha256, long size) throws java.io.IOException {
            HttpUtils.downloader().download(url, target, new Checks(null, sha256, size));
        }
    }
}
