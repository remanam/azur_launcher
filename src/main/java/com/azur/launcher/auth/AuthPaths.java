package com.azur.launcher.auth;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 账户凭据落盘位置。
 *
 * <p>正版（Microsoft）账户：{@code D:\Azur\SA\STEV\accounts.json}</p>
 * <p>非正版（离线）账户：{@code D:\Azur\SA\offline.json}</p>
 *
 * <p>根目录可用系统属性 {@code -Dazur.storage.root=<目录>} 覆盖，默认 {@code D:\Azur}。</p>
 */
public final class AuthPaths {

    private static final String DEFAULT_ROOT = "D:\\Azur";

    private AuthPaths() {
    }

    /** 凭据根目录（默认 D:\Azur）。 */
    public static Path root() {
        String override = System.getProperty("azur.storage.root", "").trim();
        String base = override.isEmpty() ? DEFAULT_ROOT : override;
        return Paths.get(base).toAbsolutePath().normalize();
    }

    /** 正版账户目录：D:\Azur\SA\STEV */
    public static Path msaDir() {
        return root().resolve("SA").resolve("STEV");
    }

    /** 非正版（离线）账户目录：D:\Azur\SA */
    public static Path offlineDir() {
        return root().resolve("SA");
    }

    /** 正版账户文件：D:\Azur\SA\STEV\accounts.json */
    public static Path msaFile() {
        return msaDir().resolve("accounts.json");
    }

    /** 非正版账户文件：D:\Azur\SA\offline.json */
    public static Path offlineFile() {
        return offlineDir().resolve("offline.json");
    }
}
