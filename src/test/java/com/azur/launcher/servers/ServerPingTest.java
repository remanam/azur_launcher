package com.azur.launcher.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ServerPingTest {

    private static final String STATUS = "{\"version\":{\"name\":\"1.21.1\"},\"players\":{\"online\":3,\"max\":20},"
            + "\"description\":{\"text\":\"Hello \",\"extra\":[{\"text\":\"Azur\"}]}}";

    @Test
    void varIntRoundTrip() throws IOException {
        for (int value : new int[] {0, 1, 127, 128, 767, 300000, Integer.MAX_VALUE}) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ServerPing.writeVarInt(new DataOutputStream(buf), value);
            assertEquals(value, ServerPing.readVarInt(new DataInputStream(new ByteArrayInputStream(buf.toByteArray()))));
        }
    }

    @Test
    void parsesStatusAndFlattensTheMotd() {
        ServerPing.Result result = ServerPing.parseStatus(STATUS, 42);
        assertTrue(result.online());
        assertEquals(3, result.playersOnline());
        assertEquals(20, result.playersMax());
        assertEquals("Hello Azur", result.motd());
        assertEquals("1.21.1", result.version());
        assertEquals("plain", ServerPing.parseStatus("{\"description\":\"plain\"}", 1).motd());
    }

    @Test
    void talksToAFakeServer() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread thread = new Thread(() -> serve(server));
            thread.setDaemon(true);
            thread.start();
            ServerPing.Result result = ServerPing.ping("127.0.0.1", server.getLocalPort(), 2000);
            assertTrue(result.online());
            assertEquals(3, result.playersOnline());
            assertTrue(result.latencyMs() >= 0);
        }
    }

    private static void serve(ServerSocket server) {
        try (Socket socket = server.accept()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            readPacket(in);
            readPacket(in);
            byte[] json = STATUS.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream bodyOut = new DataOutputStream(body);
            ServerPing.writeVarInt(bodyOut, 0);
            ServerPing.writeVarInt(bodyOut, json.length);
            bodyOut.write(json);
            ServerPing.writeVarInt(out, body.size());
            out.write(body.toByteArray());
            byte[] ping = readPacket(in);
            ServerPing.writeVarInt(out, ping.length);
            out.write(ping);
            out.flush();
        } catch (IOException ignored) {
            // the client side asserts on the result
        }
    }

    private static byte[] readPacket(DataInputStream in) throws IOException {
        byte[] body = new byte[ServerPing.readVarInt(in)];
        in.readFully(body);
        return body;
    }

    @Test
    void closedPortIsOffline() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        assertFalse(ServerPing.ping("127.0.0.1", port, 500).online());
    }
}
