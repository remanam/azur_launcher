package com.azur.launcher.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azur.launcher.keys.KeyTrust;
import com.azur.launcher.keys.Keyset;
import com.azur.launcher.keys.KeysetTestSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** launcher.json on the keyset path: one signature by a valid launcher key; the legacy signature is not enough. */
class UpdateKeysetTest {

    @TempDir
    Path dir;
    final KeysetTestSupport ks = new KeysetTestSupport();
    final KeyPair servers = KeysetTestSupport.newKey();
    final KeyPair launcher = KeysetTestSupport.newKey();
    final Clock clock = Clock.fixed(KeysetTestSupport.NOW, ZoneOffset.UTC);
    final byte[] jar = "new-launcher".getBytes(StandardCharsets.UTF_8);

    private Keyset keyset(long version, String... revoked) throws Exception {
        KeysetTestSupport.Doc d = KeysetTestSupport.doc().version(version).key("s1", Keyset.SERVERS, servers)
                .key("l1", Keyset.LAUNCHER, launcher);
        for (String r : revoked) {
            d.revoke(r);
        }
        return Keyset.open(KeysetTestSupport.envelope(d, ks.primary), ks.roots, null, KeysetTestSupport.NOW);
    }

    /** launcher.json with the legacy signature (servers key) and, optionally, a keyset signature. */
    private byte[] manifest(String version, KeyPair keysetSigner) throws Exception {
        UpdateManifest m = UpdateTestSupport.manifest(version, jar, "1.0.0", false);
        String legacy = new String(UpdateTestSupport.signed(m, servers), StandardCharsets.UTF_8);
        if (keysetSigner == null) {
            return legacy.getBytes(StandardCharsets.UTF_8);
        }
        String sig = KeysetTestSupport.sign(keysetSigner.getPrivate(), m.signedMessage().getBytes(StandardCharsets.UTF_8));
        return (legacy.substring(0, legacy.length() - 1) + ",\"signatures\":[{\"key_id\":\"x\",\"sig\":\"" + sig + "\"}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private UpdateService.Opener opener(KeyTrust trust) {
        return UpdateService.forTrust(() -> trust, clock);
    }

    @Test
    void keysetModeNeedsALauncherKeySignature() throws Exception {
        KeyTrust trust = new KeyTrust(KeyTrust.Mode.KEYSET, keyset(1), servers.getPublic(), "");
        assertEquals("1.1.0", opener(trust).open(manifest("1.1.0", launcher)).version());
        assertThrows(UpdateException.class, () -> opener(trust).open(manifest("1.1.0", null)), "legacy signature only");
        assertThrows(UpdateException.class, () -> opener(trust).open(manifest("1.1.0", servers)), "servers key is not a launcher key");
        assertThrows(UpdateException.class, () -> opener(trust).open(manifest("1.1.0", KeysetTestSupport.newKey())), "unlisted key");
        String tampered = new String(manifest("1.1.0", launcher), StandardCharsets.UTF_8).replace("\"1.1.0\"", "\"9.9.9\"");
        assertThrows(UpdateException.class, () -> opener(trust).open(tampered.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void stolenLauncherKeyStopsWorkingOnceRevoked() throws Exception {
        KeyTrust revoked = new KeyTrust(KeyTrust.Mode.KEYSET, keyset(2, "l1"), servers.getPublic(), "");
        assertThrows(UpdateException.class, () -> opener(revoked).open(manifest("1.1.0", launcher)));
    }

    @Test
    void transitionAndLockedRefuseLegacyOnlyJarsAcceptIt() throws Exception {
        assertThrows(UpdateException.class, () -> opener(new KeyTrust(KeyTrust.Mode.TRANSITION, null, servers.getPublic(), ""))
                .open(manifest("1.1.0", launcher)));
        assertThrows(UpdateException.class, () -> opener(new KeyTrust(KeyTrust.Mode.LOCKED, null, servers.getPublic(), ""))
                .open(manifest("1.1.0", launcher)));
        assertEquals("1.1.0", opener(new KeyTrust(KeyTrust.Mode.LEGACY, null, servers.getPublic(), ""))
                .open(manifest("1.1.0", null)).version());
    }

    @Test
    void olderOrSameVersionIsNeverDownloaded() throws Exception {
        KeyTrust trust = new KeyTrust(KeyTrust.Mode.KEYSET, keyset(1), servers.getPublic(), "");
        byte[] older = manifest("1.0.5", launcher);
        int[] downloads = {0};
        UpdateService.Transport transport = new UpdateService.Transport() {
            @Override
            public byte[] get(String url) {
                return older;
            }

            @Override
            public void download(String url, Path target, String sha256, long size) throws IOException {
                downloads[0]++;
                Files.write(target, jar);
            }
        };
        UpdateService service = new UpdateService(UpdateStore.at(dir, System::currentTimeMillis, 60_000), opener(trust),
                "https://example.test/dl/launcher.json", transport, "1.0.6", "1.0.0", () -> false, line -> { });
        assertEquals(UpdateService.Kind.UP_TO_DATE, service.check().kind());
        assertEquals(0, downloads[0]);
    }
}
