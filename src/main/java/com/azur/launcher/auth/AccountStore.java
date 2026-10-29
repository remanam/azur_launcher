package com.azur.launcher.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class AccountStore {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path file;
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

    public AccountStore(Path file) {
        this.file = file;
        load();
    }

    public Path getFile() {
        return file;
    }

    public synchronized void load() {
        Data loaded = null;
        try {
            if (Files.exists(file)) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                loaded = GSON.fromJson(json, Data.class);
            }
        } catch (Exception ignored) {
        }
        if (loaded == null) {
            loaded = new Data();
        }
        if (loaded.getMicrosoft() == null) {
            loaded.setMicrosoft(new ArrayList<>());
        }
        if (loaded.getSelectedUuid() == null) {
            loaded.setSelectedUuid("");
        }
        data = loaded;
    }

    public synchronized void save() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling("accounts.json.tmp");
        Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
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
