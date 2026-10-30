package com.azur.launcher.ui;

import com.azur.launcher.auth.AccountStore;
import com.azur.launcher.config.AppDirs;
import com.azur.launcher.config.ConfigManager;
import com.azur.launcher.config.LauncherConfig;
import com.azur.launcher.i18n.I18n;
import com.azur.launcher.net.ProxySettings;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.net.URL;

/** Main window: title bar, navigation (Servers, Game, Accounts, Settings, About), the active page and the status bar. */
public class LauncherApp extends Application {

    static final String VERSION = "1.0.0";
    private static final String[] FONTS = {"philosopher_bold", "pt_sans_web_regular", "pt_sans_web_bold"};

    private enum Page { HOME, SERVERS, ACCOUNTS, SETTINGS, ABOUT }

    private HBox[] navItems;

    private AppContext ctx;
    private StackPane sceneRoot;
    private Page current = Page.HOME;
    private HomePage homePage;

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
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle(I18n.tr("app.title"));
        stage.getIcons().add(new Image(getClass().getResource("/img/logo.png").toExternalForm()));
        stage.setScene(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        stage.setOnCloseRequest(event -> ctx.saveConfig());
        build();
        stage.show();
        ctx.log("Azur Launcher started, data folder: " + dirs.root());
    }

    /** (Re)creates all pages; used at start and after a language change. */
    private void build() {
        stage().setTitle(I18n.tr("app.title"));
        BorderPane window = new BorderPane();
        window.getStyleClass().add("window-root");
        window.setTop(new TitleBar(stage(), I18n.tr("app.title"), this::shutdown));
        window.setLeft(navigation());
        window.setBottom(statusBar());
        window.setCenter(content());
        sceneRoot.getChildren().setAll(window);
    }

    private Stage stage() {
        return ctx.stage;
    }

    private Node content() {
        StackPane area = new StackPane();
        area.getStyleClass().add("content-area");
        PlayController play = new PlayController(ctx);
        homePage = new HomePage(ctx, play, PackState.live(ctx));
        area.getChildren().addAll(homePage.node(), new ServersPage(ctx, play).node(),
                new AccountsPage(ctx).node(), new SettingsPage(ctx, this::build, play).node(), new AboutPage().node());
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
        if (page == Page.HOME && homePage != null) {
            homePage.onShown();
        }
        for (HBox other : navItems) {
            other.getStyleClass().remove("selected");
        }
        navItems[page.ordinal()].getStyleClass().add("selected");
    }

    private VBox navigation() {
        VBox drawer = new VBox(2);
        drawer.getStyleClass().add("nav-drawer");
        HBox[] items = new HBox[Page.values().length];
        items[Page.HOME.ordinal()] = navItem("icon-home", I18n.tr("nav.home"));
        items[Page.SERVERS.ordinal()] = navItem("icon-versions", I18n.tr("nav.servers"));
        items[Page.ACCOUNTS.ordinal()] = navItem("icon-account", I18n.tr("nav.accounts"));
        items[Page.SETTINGS.ordinal()] = navItem("icon-settings", I18n.tr("nav.settings"));
        items[Page.ABOUT.ordinal()] = navItem("icon-info", I18n.tr("nav.about"));
        for (Page page : Page.values()) {
            HBox item = items[page.ordinal()];
            item.setOnMouseClicked(event -> navigate(page));
        }
        navItems = items;
        items[current.ordinal()].getStyleClass().add("selected");
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        Label footer = UiKit.label("Java " + Runtime.version().feature() + " / JavaFX", "nav-footer-label");
        drawer.getChildren().addAll(items);
        drawer.getChildren().addAll(spacer, footer);
        return drawer;
    }

    private HBox navItem(String icon, String text) {
        HBox item = new HBox(12, Icons.of(icon, 16), new Label(text));
        item.getStyleClass().add("nav-item");
        item.setAlignment(Pos.CENTER_LEFT);
        return item;
    }

    private HBox statusBar() {
        Label status = UiKit.label(I18n.tr("status.ready"), "status-text");
        ProgressBar progress = new ProgressBar(0);
        progress.getStyleClass().add("download-progress");
        progress.setVisible(false);
        ctx.bindStatus(status::setText);
        ctx.bindProgress(fraction -> {
            progress.setVisible(fraction >= 0);
            progress.setProgress(fraction);
        });
        HBox bar = new HBox(12, status, UiKit.spacer(), progress, UiKit.label(I18n.tr("app.version", VERSION), "status-text"));
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void shutdown() {
        ctx.saveConfig();
        ctx.shutdown();
        Platform.exit();
    }
}
