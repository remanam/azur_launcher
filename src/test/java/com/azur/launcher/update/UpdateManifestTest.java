package com.azur.launcher.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import org.junit.jupiter.api.Test;

class UpdateManifestTest {

    private final byte[] jar = "jar-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void goodManifestOpens() throws Exception {
        KeyPair key = UpdateTestSupport.newKey();
        UpdateManifest m = UpdateTestSupport.manifest("1.1.0", jar, "1.0.0", true);
        UpdateManifest opened = UpdateManifest.open(UpdateTestSupport.signed(m, key), key.getPublic());
        assertEquals(m, opened);
    }

    @Test
    void tamperedFieldsAreRejected() throws Exception {
        KeyPair key = UpdateTestSupport.newKey();
        UpdateManifest m = UpdateTestSupport.manifest("1.1.0", jar, "1.0.0", false);
        String good = new String(UpdateTestSupport.signed(m, key), StandardCharsets.UTF_8);
        String evilSha = UpdateTestSupport.sha256("other".getBytes(StandardCharsets.UTF_8));
        for (String bad : new String[] {
                good.replace(m.sha256(), evilSha),
                good.replace("\"version\":\"1.1.0\"", "\"version\":\"9.0.0\""),
                good.replace("azur-launcher.jar", "evil.jar"),
                good.replace("\"required\":false", "\"required\":true"),
                good.replace("\"min_wrapper\":\"1.0.0\"", "\"min_wrapper\":\"0.1\""),
                good.replace("notes", "other")}) {
            assertThrows(UpdateException.class, () -> UpdateManifest.open(bad.getBytes(StandardCharsets.UTF_8), key.getPublic()));
        }
    }

    @Test
    void wrongKeyAndGarbageAreRejected() throws Exception {
        KeyPair key = UpdateTestSupport.newKey();
        byte[] signed = UpdateTestSupport.signed(UpdateTestSupport.manifest("1.1.0", jar, "1.0.0", false), key);
        assertThrows(UpdateException.class, () -> UpdateManifest.open(signed, UpdateTestSupport.newKey().getPublic()));
        assertThrows(UpdateException.class, () -> UpdateManifest.open("{}".getBytes(StandardCharsets.UTF_8), key.getPublic()));
    }

    /** Same string as TestMessageIsPinned in tools/updater/internal/launcherman: both signers must agree. */
    @Test
    void messageMatchesTheGoSigner() {
        UpdateManifest m = new UpdateManifest("1.1.0", "a".repeat(64), 100, "2026-11-02",
                "https://example.test/dl/azur-launcher.jar", "1.0.0", false, "");
        assertEquals("azur-launcher-update/1\n1.1.0\n" + "a".repeat(64) + "\n100\nhttps://example.test/dl/azur-launcher.jar\n"
                + "1.0.0\nfalse\n2026-11-02\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                m.signedMessage());
    }
}
