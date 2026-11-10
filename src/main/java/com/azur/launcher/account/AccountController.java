package com.azur.launcher.account;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Runs the sign-in in the background and publishes {@link AccountFlowState}. No window types: the UI passes the executor,
 * the publisher (hops to the FX thread) and the "open the browser" callback.
 */
public final class AccountController {

    private final SiteAuth auth;
    private final Executor io;
    private final Consumer<AccountFlowState> publish;
    private final Consumer<String> openUrl;
    private final Supplier<String> deviceName;
    private final Consumer<Optional<SiteSession>> onChanged;
    private volatile AccountFlowState state = AccountFlowState.signedOut(null);
    private volatile AtomicBoolean cancel = new AtomicBoolean();

    public AccountController(SiteAuth auth, Executor io, Consumer<AccountFlowState> publish, Consumer<String> openUrl,
                             Supplier<String> deviceName, Consumer<Optional<SiteSession>> onChanged) {
        this.auth = auth;
        this.io = io;
        this.publish = publish;
        this.openUrl = openUrl;
        this.deviceName = deviceName;
        this.onChanged = onChanged;
    }

    public AccountFlowState state() {
        return state;
    }

    /** Shows the stored sign-in (call once at start). */
    public void load() {
        set(AccountFlowState.of(auth.session()));
    }

    public void signIn() {
        if (!state.canStart()) {
            return;
        }
        AtomicBoolean mine = new AtomicBoolean();
        cancel = mine;
        set(AccountFlowState.starting());
        io.execute(() -> run(mine));
    }

    /** Password sign-in; the array is wiped by {@link SiteAuth#login}. A second click while running does nothing (the array is wiped here). */
    public void signInWithPassword(String login, char[] password, String lang) {
        if (!state.canStart()) {
            java.util.Arrays.fill(password, '\0');
            return;
        }
        set(AccountFlowState.starting());
        io.execute(() -> {
            try {
                SiteSession done = auth.login(login, password, deviceName.get(), lang);
                set(AccountFlowState.signedIn(done));
                onChanged.accept(Optional.of(done));
            } catch (SiteException e) {
                set(AccountFlowState.signedOut(SiteErrors.key(e)));
            } catch (IOException | RuntimeException e) {
                set(AccountFlowState.signedOut("account.err.generic"));
            }
        });
    }

    private void run(AtomicBoolean mine) {
        try {
            SiteAuth.Pending pending = auth.begin(deviceName.get());
            if (mine.get()) {
                return;
            }
            set(AccountFlowState.waiting(pending));
            openUrl.accept(pending.started().verifyUrl());
            Optional<SiteSession> done = auth.await(pending, mine, s -> { });
            if (done.isPresent() && !mine.get()) {
                set(AccountFlowState.signedIn(done.get()));
                onChanged.accept(done);
            }
        } catch (SiteException e) {
            if (!mine.get()) {
                set(AccountFlowState.signedOut(SiteErrors.key(e)));
            }
        } catch (IOException | RuntimeException e) {
            if (!mine.get()) {
                set(AccountFlowState.signedOut("account.err.generic"));
            }
        }
    }

    /** «Отмена» while the code is shown. */
    public void cancel() {
        cancel.set(true);
        set(AccountFlowState.signedOut(null));
    }

    /** «Выйти»: deletes the key and the refresh token from this computer. */
    public void signOut() {
        cancel.set(true);
        try {
            auth.signOut();
            set(AccountFlowState.signedOut(null));
        } catch (IOException e) {
            set(AccountFlowState.signedOut("account.err.generic"));
        }
        onChanged.accept(Optional.empty());
    }

    /** The saved sign-in was refused by the site (device revoked): drop it and say why. */
    public void sessionRefused(String errorKey) {
        set(AccountFlowState.signedOut(errorKey));
        onChanged.accept(Optional.empty());
    }

    private void set(AccountFlowState value) {
        state = value;
        publish.accept(value);
    }
}
