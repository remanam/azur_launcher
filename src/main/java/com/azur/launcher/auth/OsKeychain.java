package com.azur.launcher.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@link SecretStore} on the OS facilities, through their own command line tools (no extra dependency):
 * macOS Keychain ({@code security}), Linux Secret Service ({@code secret-tool}, libsecret), Windows DPAPI
 * ({@code powershell}, blob kept in a file that only the same Windows user can decrypt).
 */
final class OsKeychain implements SecretStore {

    private static final String SERVICE = "azur-launcher";

    private enum Kind { MAC, LINUX, WINDOWS }

    private final Kind kind;
    private final CommandRunner runner;
    private final Path blobDir;

    private OsKeychain(Kind kind, CommandRunner runner, Path blobDir) {
        this.kind = kind;
        this.runner = runner;
        this.blobDir = blobDir;
    }

    /** The store for the running OS, or empty when the OS has none we support. */
    static Optional<SecretStore> forCurrentOs(Path blobDir) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return Optional.of(new OsKeychain(Kind.MAC, CommandRunner.SYSTEM, blobDir));
        }
        if (os.contains("win")) {
            return Optional.of(new OsKeychain(Kind.WINDOWS, CommandRunner.SYSTEM, blobDir));
        }
        if (os.contains("linux") || os.contains("nux")) {
            return Optional.of(new OsKeychain(Kind.LINUX, CommandRunner.SYSTEM, blobDir));
        }
        return Optional.empty();
    }

    static OsKeychain forTests(String os, CommandRunner runner, Path blobDir) {
        return new OsKeychain(Kind.valueOf(os), runner, blobDir);
    }

    @Override
    public Optional<String> get(String name) {
        try {
            return switch (kind) {
                case MAC -> okOut(runner.run(List.of("security", "find-generic-password", "-s", SERVICE,
                        "-a", name, "-w"), null));
                case LINUX -> okOut(runner.run(List.of("secret-tool", "lookup", "service", SERVICE,
                        "account", name), null));
                case WINDOWS -> windowsGet(name);
            };
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void put(String name, String value) throws IOException {
        CommandRunner.Result result = switch (kind) {
            // interactive mode reads the command from stdin so the secret is not visible in the process list
            case MAC -> runner.run(List.of("security", "-i"), "add-generic-password -U -s " + SERVICE
                    + " -a " + name + " -w " + value + "\n");
            case LINUX -> runner.run(List.of("secret-tool", "store", "--label=Azur Launcher", "service", SERVICE,
                    "account", name), value);
            case WINDOWS -> windowsPut(name, value);
        };
        if (result.exit() != 0) {
            throw new IOException("Keychain write failed (" + kind + ")");
        }
    }

    private static Optional<String> okOut(CommandRunner.Result r) {
        String v = r.out().strip();
        return r.exit() == 0 && !v.isEmpty() ? Optional.of(v) : Optional.empty();
    }

    private static final String PS = "powershell";

    private CommandRunner.Result windowsPut(String name, String value) throws IOException {
        CommandRunner.Result r = runner.run(List.of(PS, "-NoProfile", "-NonInteractive", "-Command",
                "ConvertTo-SecureString ([Console]::In.ReadToEnd()) -AsPlainText -Force | ConvertFrom-SecureString"),
                value);
        if (r.exit() == 0) {
            Files.createDirectories(blobDir);
            Files.writeString(blobDir.resolve(name + ".dpapi"), r.out().strip(), StandardCharsets.UTF_8);
        }
        return r;
    }

    private Optional<String> windowsGet(String name) throws IOException {
        Path file = blobDir.resolve(name + ".dpapi");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return okOut(runner.run(List.of(PS, "-NoProfile", "-NonInteractive", "-Command",
                "$s = ConvertTo-SecureString ([Console]::In.ReadToEnd().Trim()); "
                        + "[Runtime.InteropServices.Marshal]::PtrToStringAuto("
                        + "[Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))"),
                Files.readString(file, StandardCharsets.UTF_8)));
    }
}
