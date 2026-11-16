package com.azur.launcher.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** What the launcher actually installed for an instance ({@code installed.json}); compared with the pin. */
public record InstalledState(String minecraft, String neoforge, String installerSha256) {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Missing or unreadable state counts as "nothing installed". */
    public static InstalledState read(Path file) {
        try {
            InstalledState state = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), InstalledState.class);
            return Objects.isNull(state) ? new InstalledState(null, null, null) : state;
        } catch (IOException | RuntimeException e) {
            return new InstalledState(null, null, null);
        }
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
