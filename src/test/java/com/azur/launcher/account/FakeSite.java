package com.azur.launcher.account;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** In-memory stand-in for the site API: scripted answers, records every call. */
final class FakeSite implements SiteApi {
    final List<String> calls = new ArrayList<>();
    int pendingPolls;
    SiteException pollError;
    SiteException ticketError;
    SiteException rotateError;
    String lastPubkey = "";
    String lastRotateProof = "";
    String lastRotateKey = "";
    int ticketNo;
    int refreshNo = 1;

    @Override
    public Started start(String name, String pubkey) {
        calls.add("start");
        lastPubkey = pubkey;
        return new Started("device-secret", "ABCD-EFGH", DeviceKey.fingerprintOf(java.util.Base64.getUrlDecoder().decode(pubkey)),
                "https://example.test/link?code=ABCDEFGH", 600, 3);
    }

    @Override
    public Polled poll(String code) throws SiteException {
        calls.add("poll");
        if (pollError != null) {
            throw pollError;
        }
        if (pendingPolls-- > 0) {
            return new Polled(false, "", "", "");
        }
        return new Polled(true, "dev1", "refresh-1", "Art");
    }

    @Override
    public Ticket ticket(String deviceId, String refresh, String server) throws SiteException {
        calls.add("ticket:" + refresh);
        if (ticketError != null) {
            throw ticketError;
        }
        return new Ticket("ticket-" + (++ticketNo), "Art", "refresh-" + (++refreshNo));
    }

    @Override
    public String rotate(String deviceId, String refresh, String newKey, String proof) throws SiteException {
        calls.add("rotate");
        lastRotateKey = newKey;
        lastRotateProof = proof;
        if (rotateError != null) {
            throw rotateError;
        }
        return "refresh-rotated";
    }

    static SessionStorage memory() {
        java.util.concurrent.atomic.AtomicReference<SiteSession> box = new java.util.concurrent.atomic.AtomicReference<>();
        return new SessionStorage() {
            @Override
            public java.util.Optional<SiteSession> read() {
                return java.util.Optional.ofNullable(box.get());
            }

            @Override
            public void write(SiteSession s) {
                box.set(s);
            }

            @Override
            public void clear() {
                box.set(null);
            }
        };
    }

    static Supplier<Long> unused() {
        return () -> 0L;
    }
}
