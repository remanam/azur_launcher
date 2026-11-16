package com.azur.launcher.ui;

import com.azur.launcher.auth.AccountStore;
import com.azur.launcher.AppVersion;
import com.azur.launcher.config.AppDirs;
import com.azur.launcher.update.UpdateStore;
import com.azur.launcher.config.ConfigManager;
import com.azur.launcher.config.LauncherConfig;
import com.azur.launcher.i18n.I18n;
import com.azur.launcher.net.ProxySettings;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.net.URL;
import java.util.Objects;

/** Main window: title bar and one of two pages: Play (home) and «Дополнительно» (the gear). */
public class LauncherApp extends Application {

    static final String VERSION = AppVersion.APP;
    private static final String[] FONTS = {"philosopher_bold", "pt_sans_web_regular", "pt_sans_web_bold"};

    private enum Page { HOME, ADVANCED }

    private AppContext ctx;
    private StackPane sceneRoot;
    private Page current = Page.HOME;
    private HomePage homePage;
    private PlayController play;
    private UpdateController updates;
    private FeedController feeds;

    @Override
    public void start(Stage stage) {
        AppDirs dirs = AppDirs.system();
        ConfigManager configManager = new ConfigManager(dirs);
        LauncherConfig config = configManager.load();
        I18n.setLanguage(config.getLanguage());
        ctx = new AppContext(dirs, configManager, config, AccountStore.system(dirs.accountsFile()),
                new ProxySettings(dirs.accountsFile().resolveSibling("proxy-settings.json"),
                        dirs.accountsFile().resolveSibling("auth-settings.json")),
                getHostServices(), stage);

        for (String font : FONTS) {
            Font.loadFont(getClass().getResource("/fonts/" + font + ".ttf").toExternalForm(), 14);
        }
        sceneRoot = new StackPane();
        sceneRoot.setPadding(new Insets(10));
        Scene scene = new Scene(sceneRoot, 1040, 720, Color.TRANSPARENT);
        URL stylesheet = getClass().getResource("/app.css");
        if (Objects.nonNull(stylesheet)) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle(I18n.tr("app.title"));
        stage.getIcons().add(new Image(getClass().getResource("/img/logo.png").toExternalForm()));
        stage.setScene(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        stage.setOnCloseRequest(event -> ctx.saveConfig());
        UpdateStore updateStore = UpdateStore.at(dirs.root().resolve("updates"));
        updates = UpdateController.create(ctx, updateStore,
                () -> Objects.nonNull(play) && play.phase() != PlayController.Phase.IDLE);
        feeds = FeedController.create(ctx, () -> Objects.nonNull(play) && play.phase() != PlayController.Phase.IDLE);
        build();
        stage.show();
        updateStore.confirmStarted(AppVersion.APP);
        ctx.telemetry.report("start", true, "", 0);
        if (Objects.nonNull(updates)) {
            updates.start();
        }
        if (Objects.nonNull(feeds)) {
            feeds.start();
        }
        ctx.log("Azur Launcher started, data folder: " + dirs.root());
    }

    /** (Re)creates all pages; used at start and after a language change. */
    private void build() {
        stage().setTitle(I18n.tr("app.title"));
        BorderPane window = new BorderPane();
        window.getStyleClass().add("window-root");
        window.setTop(new TitleBar(stage(), I18n.tr("app.title"), this::shutdown));
        window.setCenter(content());
        sceneRoot.getChildren().setAll(window);
    }

    private Stage stage() {
        return ctx.stage;
    }

    private Node content() {
        StackPane area = new StackPane();
        area.getStyleClass().add("content-area");
        play = new PlayController(ctx);
        java.util.function.Supplier<UpdateNotice.Restart> restart =
                () -> Objects.isNull(updates) ? UpdateNotice.Restart.FAILED : updates.restart(this::shutdown);
        homePage = new HomePage(ctx, play, PackState.live(ctx), () -> navigate(Page.ADVANCED), restart, this::shutdown);
        area.getChildren().addAll(homePage.node(),
                new AdvancedPage(ctx, this::build, play, () -> navigate(Page.HOME), updates, restart).node());
        if (Objects.nonNull(updates)) {
            updates.onStatus(homePage::showUpdate);
        }
        if (Objects.nonNull(feeds)) {
            feeds.onFeed(homePage::showFeed);
        }
        showPage(area, current);
        return area;
    }

    private void showPage(StackPane area, Page page) {
        current = page;
        for (int i = 0; i < area.getChildren().size(); i++) {
            boolean visible = i == page.ordinal();
            area.getChildren().get(i).setVisible(visible);
            area.getChildren().get(i).setManaged(visible);
        }
    }

    private void navigate(Page page) {
        StackPane area = (StackPane) ((BorderPane) sceneRoot.getChildren().get(0)).getCenter();
        showPage(area, page);
        if (page == Page.HOME && Objects.nonNull(homePage)) {
            homePage.onShown();
        }
    }

    private void shutdown() {
        ctx.saveConfig();
        if (Objects.nonNull(updates)) {
            updates.stop();
        }
        if (Objects.nonNull(feeds)) {
            feeds.stop();
        }
        ctx.shutdown();
        Platform.exit();
    }
}
