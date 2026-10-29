package com.azur.launcher.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class ServerListVerifierTest {

    static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    static final String PAYLOAD = "{\"schema\":1,\"servers\":[{\"address\":\"a.example\",\"id\":\"a\",\"name\":\"A\","
            + "\"port\":0},{\"address\":\"b.example\",\"id\":\"b\",\"name\":\"B\",\"port\":25570}],"
            + "\"validUntil\":\"2027-01-01T00:00:00Z\"}";

    static KeyPair newKey() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static byte[] envelope(String payload, KeyPair key) throws Exception {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(bytes);
        String json = "{\"payload\":\"" + Base64.getEncoder().encodeToString(bytes) + "\",\"sig\":\""
                + Base64.getEncoder().encodeToString(signer.sign()) + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static ServerListException failure(byte[] env, KeyPair key) {
        return assertThrows(ServerListException.class, () -> ServerListVerifier.open(env, key.getPublic(), NOW));
    }

    @Test
    void validListParsesAndDefaultsThePort() throws Exception {
        KeyPair key = newKey();
        ServerList list = ServerListVerifier.open(envelope(PAYLOAD, key), key.getPublic(), NOW);
        assertEquals(2, list.servers().size());
        assertEquals(25565, list.servers().get(0).port());
        assertEquals("b.example:25570", list.servers().get(1).hostPort());
        assertEquals("a.example", list.servers().get(0).hostPort());
    }

    @Test
    void wrongKeyIsRejected() throws Exception {
        KeyPair signer = newKey();
        KeyPair other = newKey();
        assertEquals(ServerListException.Reason.BAD_SIGNATURE, failure(envelope(PAYLOAD, signer), other).reason());
    }

    @Test
    void tamperedPayloadIsRejected() throws Exception {
        KeyPair key = newKey();
        String env = new String(envelope(PAYLOAD, key), StandardCharsets.UTF_8);
        String forged = Base64.getEncoder().encodeToString(PAYLOAD.replace("a.example", "evil.example")
                .getBytes(StandardCharsets.UTF_8));
        String payloadB64 = Base64.getEncoder().encodeToString(PAYLOAD.getBytes(StandardCharsets.UTF_8));
        byte[] tampered = env.replace(payloadB64, forged).getBytes(StandardCharsets.UTF_8);
        assertEquals(ServerListException.Reason.BAD_SIGNATURE, failure(tampered, key).reason());
    }

    @Test
    void expiredListIsRejectedButCanBeReadForTheCache() throws Exception {
        KeyPair key = newKey();
        byte[] env = envelope(PAYLOAD, key);
        Instant later = Instant.parse("2027-06-01T00:00:00Z");
        ServerListException e = assertThrows(ServerListException.class,
                () -> ServerListVerifier.open(env, key.getPublic(), later));
        assertEquals(ServerListException.Reason.EXPIRED, e.reason());
        assertEquals(2, ServerListVerifier.openIgnoringExpiry(env, key.getPublic(), later).servers().size());
    }

    @Test
    void malformedInputIsRejected() throws Exception {
        KeyPair key = newKey();
        assertEquals(ServerListException.Reason.BAD_FORMAT, failure("not json".getBytes(), key).reason());
        assertEquals(ServerListException.Reason.BAD_FORMAT, failure("{}".getBytes(), key).reason());
        String[] badPayloads = {
                "{\"schema\":2,\"servers\":[],\"validUntil\":\"2027-01-01T00:00:00Z\"}",
                "{\"schema\":1,\"servers\":[{\"id\":\"a\",\"name\":\"A\"}],\"validUntil\":\"2027-01-01T00:00:00Z\"}",
                "{\"schema\":1,\"servers\":[{\"id\":\"a\",\"name\":\"A\",\"address\":\"x\",\"port\":70000}],"
                        + "\"validUntil\":\"2027-01-01T00:00:00Z\"}",
                "{\"schema\":1,\"servers\":[{\"id\":\"a\",\"name\":\"A\",\"address\":\"x\"},"
                        + "{\"id\":\"a\",\"name\":\"B\",\"address\":\"y\"}],\"validUntil\":\"2027-01-01T00:00:00Z\"}",
                "{\"schema\":1,\"servers\":[],\"validUntil\":\"yesterday\"}"};
        for (String payload : badPayloads) {
            assertEquals(ServerListException.Reason.BAD_FORMAT, failure(envelope(payload, key), key).reason(), payload);
        }
    }

    @Test
    void fileSignedByTheGoToolVerifiesWithTheEmbeddedKey() throws Exception {
        byte[] env;
        try (var in = getClass().getResourceAsStream("/servers_signed_go.json")) {
            env = in.readAllBytes();
        }
        ServerList list = ServerListVerifier.open(env, ServerListService.loadKey(null), NOW);
        assertEquals("isles", list.servers().get(0).id());
        assertEquals(25566, list.servers().get(0).port());
        assertTrue(list.servers().get(0).inviteRequired());
        assertEquals("Тестовый сервер", list.servers().get(0).motd());
        assertEquals(25565, list.servers().get(1).port());
    }
}
