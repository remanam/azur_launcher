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
        assertEquals(KeysetException.Reason.NEEDS_EMERGENCY,
                refused(envelope(doc().epoch(1).key("evil", Keyset.LAUNCHER, evil), ks.primary), attacker));
        // the reserve: epoch 1, primary revoked, a new delegated root
        KeyPair newRoot = newKey();
        String primaryId = Ed25519.keyId(raw(ks.primary));
        Keyset recovered = open(envelope(doc().epoch(1).key("l2", Keyset.LAUNCHER, launcher)
                .key("root2", Keyset.ROOT, newRoot, NOW.plusSeconds(365L * 86400)).revoke(primaryId), ks.reserve), attacker);
        assertTrue(recovered.byEmergency());
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
    void formatRules() throws Exception {
        assertEquals(KeysetException.Reason.BAD_FORMAT, refused(envelope(doc().key("a", Keyset.SERVERS, servers)
                .key("b", Keyset.LAUNCHER, servers), ks.primary), null), "one key in two roles");
        assertEquals(KeysetException.Reason.BAD_FORMAT, refused(envelope(doc().key("a", "admin", servers), ks.primary), null));
        assertEquals(KeysetException.Reason.BAD_FORMAT, refused(envelope(doc().key("a", Keyset.SERVERS, ks.primary), ks.reserve), null));
        // B cannot sign its own revocation; B and C together cannot revoke both (neither signature counts)
        assertEquals(KeysetException.Reason.BAD_SIGNATURE, refused(envelope(doc().revoke(Ed25519.keyId(raw(ks.reserve))), ks.reserve), null));
        assertEquals(KeysetException.Reason.BAD_SIGNATURE, refused(envelope(doc().revoke(Ed25519.keyId(raw(ks.reserve)))
                .revoke(Ed25519.keyId(raw(ks.c))), ks.reserve, ks.c), null));
        // a delegated emergency root that drops itself and revokes B and C would leave none: refused
        KeyPair e2 = newKey();
        Keyset withE2 = open(envelope(doc().key("e2", Keyset.ROOT_EMERGENCY, e2, NOW.plusSeconds(3650L * 86400)), ks.reserve), null);
        assertEquals(KeysetException.Reason.BAD_FORMAT, refused(envelope(doc().version(2).revoke(Ed25519.keyId(raw(ks.reserve)))
                .revoke(Ed25519.keyId(raw(ks.c))), e2), withE2));
        assertThrows(KeysetException.class, () -> Keyset.open(envelope(doc(), ks.primary), RootKeys.NONE, null, NOW));
    }

    @Test
    void rootKeysFile() {
        String text = "# c\nprimary " + KeysetTestSupport.b64(ks.primary) + "\nreserve " + KeysetTestSupport.b64(ks.reserve) + "\n";
        RootKeys r = RootKeys.parse(text);
        assertArrayEquals(raw(ks.primary), r.working().get(0));
        assertEquals(1, r.emergency().size(), "old two-line file");
        String a = KeysetTestSupport.b64(ks.primary);
        String b = KeysetTestSupport.b64(ks.reserve);
        String c = KeysetTestSupport.b64(ks.c);
        RootKeys three = RootKeys.parse("working " + a + "\nemergency " + b + "\nemergency " + c + "\n");
        assertEquals(2, three.emergency().size());
        RootKeys bare = RootKeys.parse(a + "\n" + b + "\n" + c + "\n");
        assertArrayEquals(raw(ks.primary), bare.working().get(0), "bare lines: first is the working root");
        assertArrayEquals(raw(ks.c), bare.emergency().get(1));
        assertEquals(RootKeys.NONE, RootKeys.parse("# empty dev file\n"));
        assertThrows(IllegalArgumentException.class, () -> RootKeys.parse("primary " + a));
        assertThrows(IllegalArgumentException.class, () -> RootKeys.parse("emergency " + b + "\nemergency " + c));
        assertThrows(IllegalArgumentException.class, () -> RootKeys.parse("working " + a + "\nemergency " + a));
        assertThrows(IllegalArgumentException.class, () -> RootKeys.parse("admin " + a + "\nemergency " + b));
    }

    @Test
    void eachEmergencyRootAloneStartsAnEpochAndRevokedRootsAreRefused() throws Exception {
        String a = Ed25519.keyId(raw(ks.primary));
        for (KeyPair e : List.of(ks.reserve, ks.c)) {
            Keyset v1 = open(envelope(doc().version(3).key("s1", Keyset.SERVERS, servers), ks.primary), null);
            byte[] rec = envelope(doc().epoch(1).version(4).key("s1", Keyset.SERVERS, servers).revoke(a), e);
            Keyset v2 = open(rec, v1);
            assertTrue(v2.byEmergency());
            open(rec, null); // a fresh install accepts it too
            assertEquals(KeysetException.Reason.BAD_SIGNATURE, refused(envelope(doc().epoch(1).version(99)
                    .key("s1", Keyset.SERVERS, servers).revoke(a), ks.primary), v2), "revoked A");
            assertEquals(KeysetException.Reason.ROLLBACK, refused(envelope(doc().epoch(0).version(1L << 40)
                    .key("s1", Keyset.SERVERS, servers), e), v2), "old epoch");
        }
    }

    @Test
    void lostEmergencyRootIsReplacedByTheOther() throws Exception {
        for (KeyPair[] pair : List.of(new KeyPair[] {ks.reserve, ks.c}, new KeyPair[] {ks.c, ks.reserve})) {
            KeyPair lost = pair[0];
            KeyPair other = pair[1];
            KeyPair replacement = newKey();
            String lostId = Ed25519.keyId(raw(lost));
            Keyset v1 = open(envelope(doc().key("s1", Keyset.SERVERS, servers), ks.primary), null);
            KeysetTestSupport.Doc rec = doc().epoch(1).version(2).key("s1", Keyset.SERVERS, servers)
                    .key("e2", Keyset.ROOT_EMERGENCY, replacement, NOW.plusSeconds(3650L * 86400)).revoke(lostId);
            assertEquals(KeysetException.Reason.BAD_SIGNATURE, refused(envelope(rec, lost), v1), "own revocation");
            byte[] anchor = envelope(rec, other);
            Keyset v2 = open(anchor, v1);
            KeysetTestSupport.Doc next = doc().epoch(2).version(3).key("s1", Keyset.SERVERS, servers)
                    .key("e2", Keyset.ROOT_EMERGENCY, replacement, NOW.plusSeconds(3650L * 86400)).revoke(lostId);
            assertEquals(KeysetException.Reason.BAD_SIGNATURE, refused(envelope(next, lost), v2), "revoked emergency root");
            open(envelope(next, replacement), v2);
            // a fresh install learns the replacement only through the anchor chain
            assertEquals(KeysetException.Reason.BAD_SIGNATURE, refused(envelope(next, replacement), null));
            Keyset fresh = open(KeysetTestSupport.withAnchors(envelope(next, replacement), anchor), null);
            assertTrue(fresh.anchored());
        }
    }

    @Test
    void workingRootLimitsUnknownRootAndAnchorRules() throws Exception {
        Keyset v1 = open(envelope(doc().key("s1", Keyset.SERVERS, servers), ks.primary), null);
        assertEquals(KeysetException.Reason.NEEDS_EMERGENCY,
                refused(envelope(doc().epoch(1).version(2).key("s1", Keyset.SERVERS, servers), ks.primary), v1));
        assertEquals(KeysetException.Reason.NEEDS_EMERGENCY, refused(envelope(doc().version(2).key("s1", Keyset.SERVERS, servers)
                .revoke(Ed25519.keyId(raw(ks.c))), ks.primary), v1), "A revoking C");
        assertEquals(KeysetException.Reason.BAD_SIGNATURE,
                refused(envelope(doc().epoch(1).version(2).key("s1", Keyset.SERVERS, servers), newKey()), v1), "unknown root");
        // an anchor signed by the working root does not count
        byte[] fakeAnchor = envelope(doc().version(2).key("s1", Keyset.SERVERS, servers), ks.primary);
        assertEquals(KeysetException.Reason.BAD_FORMAT, refused(KeysetTestSupport.withAnchors(
                envelope(doc().version(3).key("s1", Keyset.SERVERS, servers), ks.primary), fakeAnchor), null));
    }
}
