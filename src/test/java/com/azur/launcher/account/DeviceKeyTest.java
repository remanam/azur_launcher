package com.azur.launcher.account;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class DeviceKeyTest {

    private static boolean verify(String pubB64, byte[] msg, byte[] sig) throws Exception {
        byte[] raw = Base64.getUrlDecoder().decode(pubB64);
        byte[] prefix = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
        byte[] x509 = new byte[prefix.length + 32];
        System.arraycopy(prefix, 0, x509, 0, prefix.length);
        System.arraycopy(raw, 0, x509, prefix.length, 32);
        Signature v = Signature.getInstance("Ed25519");
        v.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(x509)));
        v.update(msg);
        return v.verify(sig);
    }

    @Test
    void rawKeysAre32BytesAndSignaturesVerify() throws Exception {
        DeviceKey k = DeviceKey.generate();
        assertEquals(32, Base64.getUrlDecoder().decode(k.publicKeyBase64Url()).length);
        assertEquals(32, Base64.getUrlDecoder().decode(k.seedBase64Url()).length);
        assertFalse(k.publicKeyBase64Url().contains("="), "no padding, as the site expects");
        byte[] msg = "hello".getBytes();
        assertTrue(verify(k.publicKeyBase64Url(), msg, k.sign(msg)));
    }

    @Test
    void restoreGivesTheSameKeyAndRejectsAMismatch() throws Exception {
        DeviceKey a = DeviceKey.generate();
        DeviceKey b = DeviceKey.restore(a.seedBase64Url(), a.publicKeyBase64Url());
        byte[] msg = {1, 2, 3};
        assertTrue(verify(a.publicKeyBase64Url(), msg, b.sign(msg)));
        assertEquals(a.fingerprint(), b.fingerprint());
        DeviceKey other = DeviceKey.generate();
        assertThrows(IllegalArgumentException.class, () -> DeviceKey.restore(a.seedBase64Url(), other.publicKeyBase64Url()));
        assertThrows(IllegalArgumentException.class, () -> DeviceKey.restore("short", a.publicKeyBase64Url()));
    }

    @Test
    void fingerprintHasTheSiteShape() {
        assertTrue(DeviceKey.generate().fingerprint().matches("[0-9a-f]{4}(-[0-9a-f]{4}){3}"));
        // sha256(32 zero bytes) starts 66687aadf862bd77: the same digest as site fingerprint()
        assertEquals("6668-7aad-f862-bd77", DeviceKey.fingerprintOf(new byte[32]));
    }
}
