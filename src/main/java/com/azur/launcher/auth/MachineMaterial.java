package com.azur.launcher.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** OS-bound text for the fallback vault key: machine id, user name and home folder. */
final class MachineMaterial {

    private MachineMaterial() {
    }

    static String current() {
        return machineId() + "|" + System.getProperty("user.name", "") + "|" + System.getProperty("user.home", "");
    }

    private static String machineId() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                for (String line : CommandRunner.SYSTEM.run(
                        List.of("ioreg", "-rd1", "-c", "IOPlatformExpertDevice"), null).out().split("\n")) {
                    if (line.contains("IOPlatformUUID")) {
                        return line.substring(line.indexOf('=') + 1).trim();
                    }
                }
            } else if (os.contains("win")) {
                return CommandRunner.SYSTEM.run(List.of("reg", "query",
                        "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid"), null).out().strip();
            } else {
                for (String p : new String[] {"/etc/machine-id", "/var/lib/dbus/machine-id"}) {
                    if (Files.isRegularFile(Path.of(p))) {
                        return Files.readString(Path.of(p)).strip();
                    }
                }
            }
        } catch (IOException ignored) {
            // no machine id: the key then depends on user name and home only
        }
        return "";
    }
}
