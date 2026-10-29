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
        private List<MicrosoftAccount> microsoft = new ArrayList<>();
        private String selectedUuid = "";

        public List<MicrosoftAccount> getMicrosoft() {
            return microsoft;
        }

        public void setMicrosoft(List<MicrosoftAccount> microsoft) {
            this.microsoft = microsoft == null ? new ArrayList<>() : microsoft;
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
        if (data.getMicrosoft() == null) {
            data.setMicrosoft(new ArrayList<>());
        }
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

    public synchronized List<MicrosoftAccount> microsoftAccounts() {
        return new ArrayList<>(data.getMicrosoft());
    }

    public synchronized MicrosoftAccount selectedAccount() {
        String selected = data.getSelectedUuid();
        if (selected == null || selected.isBlank()) {
            return null;
        }
        for (MicrosoftAccount account : data.getMicrosoft()) {
            if (account != null && selected.equalsIgnoreCase(account.getUuid())) {
                return account;
            }
        }
        return null;
    }

    public synchronized String selectedUuid() {
        return data.getSelectedUuid();
    }

    public synchronized void upsert(MicrosoftAccount account) {
        if (account == null || account.getUuid() == null || account.getUuid().isBlank()) {
            return;
        }
        List<MicrosoftAccount> list = data.getMicrosoft();
        for (int i = 0; i < list.size(); i++) {
            MicrosoftAccount existing = list.get(i);
            if (existing != null && account.getUuid().equalsIgnoreCase(existing.getUuid())) {
                list.set(i, account);
                return;
            }
        }
        list.add(account);
    }

    public synchronized void select(MicrosoftAccount account) {
        if (account == null) {
            return;
        }
        upsert(account);
        data.setSelectedUuid(account.getUuid());
    }

    public synchronized void clearSelection() {
        data.setSelectedUuid("");
    }

    public synchronized void remove(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return;
        }
        data.getMicrosoft().removeIf(a -> a != null && uuid.equalsIgnoreCase(a.getUuid()));
        if (uuid.equalsIgnoreCase(data.getSelectedUuid())) {
            data.setSelectedUuid("");
        }
    }

    public synchronized boolean isEmpty() {
        return data.getMicrosoft().isEmpty();
    }
}
