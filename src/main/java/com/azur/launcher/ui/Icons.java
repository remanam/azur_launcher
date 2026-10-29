package com.azur.launcher.ui;

import javafx.scene.layout.Region;

public final class Icons {

    private Icons() {
    }

    public static Region of(String name) {
        return of(name, 16);
    }

    public static Region of(String name, double size) {
        Region region = new Region();
        region.getStyleClass().add("icon");
        region.getStyleClass().add(name.startsWith("icon-") ? name : "icon-" + name);
        region.setMinSize(size, size);
        region.setPrefSize(size, size);
        region.setMaxSize(size, size);
        region.setMouseTransparent(true);
        return region;
    }
}
