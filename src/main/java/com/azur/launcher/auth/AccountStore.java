package com.azur.launcher.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class AccountStore {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private final Path legacyFile;
    private final Vault vault;
    private Data data = new Data();

    public static final class Data {
        private List<OfflineAccount> offline = new ArrayList<>();
        private String selectedUuid = "";

        public List<OfflineAccount> getOffline() {
            return offline;
        }

        public void setOffline(List<OfflineAccount> offline) {
            this.offline = offline == null ? new ArrayList<>() : offline;
        }

        public String getSelectedUuid() {
            return selectedUuid;
        }

        public void setSelectedUuid(String selectedUuid) {
            this.selectedUuid = selectedUuid == null ? "" : selectedUuid;
        }
    }

    /** The store for this machine: keychain key when the OS has one, derived key otherwise. */
    public static AccountStore system(Path legacyJsonFile) {
        Path dir = legacyJsonFile.getParent();
        return new AccountStore(legacyJsonFile, OsKeychain.forCurrentOs(dir).orElse(null),
                MachineMaterial.current());
    }

    /**
     * @param legacyJsonFile the old plain {@code accounts.json}; read once for migration, then deleted
     * @param keychain       OS secret store or null
     */
    AccountStore(Path legacyJsonFile, SecretStore keychain, String machineMaterial) {
        this.legacyFile = legacyJsonFile;
        this.vault = new Vault(legacyJsonFile.resolveSibling("accounts.vault"), keychain, machineMaterial);
        load();
    }

    public synchronized void load() {
        Data loaded = vault.read().map(b -> parse(new String(b, StandardCharsets.UTF_8))).orElse(null);
        boolean migrate = false;
        if (loaded == null && !vault.exists() && Files.isRegularFile(legacyFile)) {
            try {
                loaded = parse(Files.readString(legacyFile, StandardCharsets.UTF_8));
                migrate = loaded != null;
            } catch (IOException ignored) {
                // unreadable legacy file: start empty
            }
        }
        data = loaded == null ? new Data() : loaded;
        data.setOffline(data.getOffline());
        data.getOffline().removeIf(a -> a == null || !a.launchPermitted());
        if (data.getSelectedUuid() == null) {
            data.setSelectedUuid("");
        }
        if (migrate) {
            migrateLegacy();
        }
    }

    private static Data parse(String json) {
        try {
            return GSON.fromJson(json, Data.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Writes the encrypted vault, checks it reads back, then removes the plain file. */
    private void migrateLegacy() {
        try {
            save();
            if (vault.read().isPresent()) {
                Files.deleteIfExists(legacyFile);
            }
        } catch (IOException ignored) {
            // the plain file stays; migration is retried at the next start
        }
    }

    public synchronized void save() throws IOException {
        vault.write(GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
    }

    /** The selected account; null when none. */
    public synchronized AuthAccount selected() {
        String selected = data.getSelectedUuid();
        for (OfflineAccount account : data.getOffline()) {
            if (selected != null && selected.equalsIgnoreCase(account.getUuid())) {
                return account;
            }
        }
        return null;
    }

    public synchronized List<OfflineAccount> offlineAccounts() {
        return new ArrayList<>(data.getOffline());
    }

    /** Adds (once per nickname) and selects an offline account. */
    public synchronized void selectOffline(OfflineAccount account) {
        data.getOffline().removeIf(existing -> existing.getUuid().equalsIgnoreCase(account.getUuid()));
        data.getOffline().add(account);
        data.setSelectedUuid(account.getUuid());
    }

    public synchronized void selectExisting(AuthAccount account) {
        if (account instanceof OfflineAccount offline) {
            selectOffline(offline);
        }
    }

    public synchronized String selectedUuid() {
        return data.getSelectedUuid();
    }

    public synchronized void clearSelection() {
        data.setSelectedUuid("");
    }

    public synchronized void remove(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return;
        }
        data.getOffline().removeIf(a -> uuid.equalsIgnoreCase(a.getUuid()));
        if (uuid.equalsIgnoreCase(data.getSelectedUuid())) {
            data.setSelectedUuid("");
        }
    }

    public synchronized boolean isEmpty() {
        return data.getOffline().isEmpty();
    }
}
