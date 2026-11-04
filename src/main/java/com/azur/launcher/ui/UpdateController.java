package com.azur.launcher.ui;

import com.azur.launcher.AppVersion;
import com.azur.launcher.Launcher;
import com.azur.launcher.net.Checks;
import com.azur.launcher.net.HttpUtils;
import com.azur.launcher.servers.ServerListService;
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

/** Runs the launcher self-update check at start and every few hours, and restarts into a staged update. */
final class UpdateController {

    private static final long FIRST_DELAY_SECONDS = 3;
    private static final long PERIOD_HOURS = 4;
    private static final long HALT_AFTER_MILLIS = 2500;

    private final AppContext ctx;
    private final UpdateService service;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "azur-update");
        thread.setDaemon(true);
        return thread;
    });
    private volatile UpdateService.Status last = new UpdateService.Status(UpdateService.Kind.UP_TO_DATE, null, "");
    private Consumer<UpdateService.Status> listener = status -> { };

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
            UpdateService service = new UpdateService(store, ServerListService.appKey(ctx.dirs.serversKeyFile()),
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

    void start() {
        timer.scheduleWithFixedDelay(this::checkNow, FIRST_DELAY_SECONDS, PERIOD_HOURS * 3600, TimeUnit.SECONDS);
    }

    private void checkNow() {
        UpdateService.Status status = service.check();
        if (status.kind() == UpdateService.Kind.BUSY || status.kind() == UpdateService.Kind.FAILED) {
            return;
        }
        last = status;
        Platform.runLater(() -> listener.accept(status));
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
