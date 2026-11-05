package com.azur.launcher.keys;

import static com.azur.launcher.keys.KeysetTestSupport.NOW;
import static com.azur.launcher.keys.KeysetTestSupport.doc;
import static com.azur.launcher.keys.KeysetTestSupport.envelope;
import static com.azur.launcher.keys.KeysetTestSupport.newKey;
import static com.azur.launcher.keys.KeysetTestSupport.raw;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.util.List;

import org.junit.jupiter.api.Test;

class KeysetTest {

    private final KeysetTestSupport ks = new KeysetTestSupport();
    private final KeyPair servers = newKey();
    private final KeyPair launcher = newKey();

    private Keyset open(byte[] env, Keyset prev) throws KeysetException {
        return Keyset.open(env, ks.roots, prev, NOW);
    }

    private KeysetException.Reason refused(byte[] env, Keyset prev) {
        return assertThrows(KeysetException.class, () -> open(env, prev)).reason();
    }

    @Test
    void acceptedKeysByRole() throws Exception {
        KeyPair old = newKey();
        Keyset k = open(envelope(doc().key("s1", Keyset.SERVERS, servers).key("l1", Keyset.LAUNCHER, launcher)
                .key("s0", Keyset.SERVERS, old, NOW.minusSeconds(60)), ks.primary), null);
        List<byte[]> keys = k.keys(Keyset.SERVERS, NOW);
        assertEquals(1, keys.size(), "the expired working key is not usable");
        assertArrayEquals(raw(servers), keys.get(0));
        assertEquals(1, k.keys(Keyset.LAUNCHER, NOW).size());
        assertTrue(k.keys(Keyset.PACK, NOW).isEmpty());
        assertTrue(k.keys(Keyset.SERVERS, NOW.plusSeconds(46L * 86400)).isEmpty(), "keys of an expired keyset");
    }

    @Test
    void foreignSignerAndTamperedPayloadAreRefused() {
        assertEquals(KeysetException.Reason.BAD_SIGNATURE, refused(envelope(doc().key("s", Keyset.SERVERS, servers), newKey()), null));
        String good = new String(envelope(doc().key("s", Keyset.SERVERS, servers), ks.primary));
        String other = new String(envelope(doc().key("s", Keyset.SERVERS, newKey()), ks.primary));
        String forged = other.substring(0, other.indexOf("\"sigs\"")) + good.substring(good.indexOf("\"sigs\""));
        assertEquals(KeysetException.Reason.BAD_SIGNATURE, refused(forged.getBytes(), null));
    }

    @Test
    void expiredKeysetIsRefused() {
        assertEquals(KeysetException.Reason.EXPIRED,
                refused(envelope(doc().expires(NOW.minusSeconds(1)), ks.primary), null));
    }

    @Test
    void revokedKeyIsNotUsable() throws Exception {
        Keyset v1 = open(envelope(doc().key("s1", Keyset.SERVERS, servers), ks.primary), null);
        Keyset v2 = open(envelope(doc().version(2).key("s1", Keyset.SERVERS, servers).revoke("s1"), ks.primary), v1);
        assertTrue(v2.keys(Keyset.SERVERS, NOW).isEmpty());
        Keyset v3 = open(envelope(doc().version(3).key("s1", Keyset.SERVERS, servers)
                .revoke(KeysetTestSupport.b64(servers)), ks.primary), v2);
        assertTrue(v3.keys(Keyset.SERVERS, NOW).isEmpty(), "revoked by public key value");
    }

    @Test
    void lowerVersionAndSameVersionWithOtherContentAreRollback() throws Exception {
        Keyset v5 = open(envelope(doc().version(5).key("s1", Keyset.SERVERS, servers), ks.primary), null);
        assertEquals(KeysetException.Reason.ROLLBACK, refused(envelope(doc().version(4).key("s1", Keyset.SERVERS, servers), ks.primary), v5));
        assertEquals(KeysetException.Reason.ROLLBACK, refused(envelope(doc().version(5).key("s2", Keyset.SERVERS, newKey()), ks.primary), v5));
        // the very same keyset again is fine (a launcher refreshes it on every start)
        open(envelope(doc().version(5).key("s1", Keyset.SERVERS, servers), ks.primary), v5);
    }

    @Test
    void stolenPrimaryIsRecoveredByTheReserve() throws Exception {
        KeyPair evil = newKey();
        Keyset attacker = open(envelope(doc().version(1L << 40).key("evil", Keyset.LAUNCHER, evil), ks.primary), null);
        // the primary alone can neither start a new epoch nor touch root keys
        assertEquals(KeysetException.Reason.NEEDS_RESERVE,
                refused(envelope(doc().epoch(1).key("evil", Keyset.LAUNCHER, evil), ks.primary), attacker));
        // the reserve: epoch 1, primary revoked, a new delegated root
        KeyPair newRoot = newKey();
        String primaryId = Ed25519.keyId(raw(ks.primary));
        Keyset recovered = open(envelope(doc().epoch(1).key("l2", Keyset.LAUNCHER, launcher)
                .key("root2", Keyset.ROOT, newRoot, NOW.plusSeconds(365L * 86400)).revoke(primaryId), ks.reserve), attacker);
        assertTrue(recovered.byReserve());
        // the stolen primary is useless now, whatever the version
        assertEquals(KeysetException.Reason.BAD_SIGNATURE, refused(envelope(doc().epoch(1).version(1L << 41)
                .key("l2", Keyset.LAUNCHER, launcher).key("root2", Keyset.ROOT, newRoot, NOW.plusSeconds(365L * 86400))
                .revoke(primaryId), ks.primary), recovered));
        // the delegated root signs the next keyset in this epoch
        open(envelope(doc().epoch(1).version(2).key("l2", Keyset.LAUNCHER, launcher)
                .key("root2", Keyset.ROOT, newRoot, NOW.plusSeconds(365L * 86400)).revoke(primaryId), newRoot), recovered);
        // dropping the root revocation is refused even from the reserve
        assertEquals(KeysetException.Reason.BAD_FORMAT, refused(envelope(doc().epoch(1).version(3)
                .key("l2", Keyset.LAUNCHER, launcher), ks.reserve), recovered));
    }

    @Test
    void formatRules() {
        assertEquals(KeysetException.Reason.BAD_FORMAT, refused(envelope(doc().key("a", Keyset.SERVERS, servers)
                .key("b", Keyset.LAUNCHER, servers), ks.primary), null), "one key in two roles");
        assertEquals(KeysetException.Reason.BAD_FORMAT, refused(envelope(doc().key("a", "admin", servers), ks.primary), null));
        assertEquals(KeysetException.Reason.BAD_FORMAT, refused(envelope(doc().key("a", Keyset.SERVERS, ks.primary), ks.reserve), null));
        assertEquals(KeysetException.Reason.BAD_FORMAT, refused(envelope(doc().revoke(Ed25519.keyId(raw(ks.reserve))), ks.reserve), null));
        assertThrows(KeysetException.class, () -> Keyset.open(envelope(doc(), ks.primary), RootKeys.NONE, null, NOW));
    }

    @Test
    void rootKeysFile() {
        String text = "# c\nprimary " + KeysetTestSupport.b64(ks.primary) + "\nreserve " + KeysetTestSupport.b64(ks.reserve) + "\n";
        RootKeys r = RootKeys.parse(text);
        assertArrayEquals(raw(ks.primary), r.primary());
        assertEquals(RootKeys.NONE, RootKeys.parse("# empty dev file\n"));
        assertThrows(IllegalArgumentException.class, () -> RootKeys.parse("primary " + KeysetTestSupport.b64(ks.primary)));
    }
}
