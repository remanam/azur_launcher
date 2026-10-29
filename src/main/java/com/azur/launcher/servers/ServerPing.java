package com.azur.launcher.servers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Minecraft server list ping (handshake, status request, ping/pong). Blocking, with hard timeouts. */
public final class ServerPing {

    /** Result of one ping; {@link #OFFLINE} when the server did not answer. */
    public record Result(boolean online, int playersOnline, int playersMax, long latencyMs, String version,
                         String motd) {
        public static final Result OFFLINE = new Result(false, 0, 0, -1, "", "");
    }

    private static final int PROTOCOL = 767;
    private static final int MAX_RESPONSE = 1 << 20;

    private ServerPing() {
    }

    /** Never throws: any failure is {@link Result#OFFLINE}. */
    public static Result ping(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            writePacket(out, handshake(host, port));
            writePacket(out, new byte[] {0x00});
            String json = readStatus(in);
            long sent = System.nanoTime();
            writePacket(out, pingPacket(sent));
            readPacket(in);
            long latency = (System.nanoTime() - sent) / 1_000_000;
            return parseStatus(json, latency);
        } catch (IOException | RuntimeException e) {
            return Result.OFFLINE;
        }
    }

    static Result parseStatus(String json, long latencyMs) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject players = root.has("players") ? root.getAsJsonObject("players") : new JsonObject();
        JsonObject version = root.has("version") ? root.getAsJsonObject("version") : new JsonObject();
        return new Result(true, intOf(players, "online"), intOf(players, "max"), latencyMs,
                version.has("name") ? version.get("name").getAsString() : "",
                root.has("description") ? text(root.get("description")) : "");
    }

    private static int intOf(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsInt() : 0;
    }

    /** Flattens a chat component (string, or object with text and extra) to plain text. */
    static String text(JsonElement element) {
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        StringBuilder sb = new StringBuilder();
        if (element.isJsonObject()) {
            JsonObject o = element.getAsJsonObject();
            if (o.has("text")) {
                sb.append(o.get("text").getAsString());
            }
            if (o.has("extra")) {
                for (JsonElement part : o.getAsJsonArray("extra")) {
                    sb.append(text(part));
                }
            }
        }
        return sb.toString();
    }

    private static byte[] handshake(String host, int port) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        writeVarInt(out, 0x00);
        writeVarInt(out, PROTOCOL);
        byte[] name = host.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, name.length);
        out.write(name);
        out.writeShort(port);
        writeVarInt(out, 1);
        return buf.toByteArray();
    }

    private static byte[] pingPacket(long token) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buf);
        writeVarInt(out, 0x01);
        out.writeLong(token);
        return buf.toByteArray();
    }

    private static String readStatus(DataInputStream in) throws IOException {
        DataInputStream packet = readPacket(in);
        if (readVarInt(packet) != 0x00) {
            throw new IOException("Unexpected packet");
        }
        int length = readVarInt(packet);
        if (length < 0 || length > MAX_RESPONSE) {
            throw new IOException("Bad status length");
        }
        byte[] bytes = new byte[length];
        packet.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static DataInputStream readPacket(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length <= 0 || length > MAX_RESPONSE) {
            throw new IOException("Bad packet length");
        }
        byte[] body = new byte[length];
        in.readFully(body);
        return new DataInputStream(new java.io.ByteArrayInputStream(body));
    }

    private static void writePacket(DataOutputStream out, byte[] body) throws IOException {
        writeVarInt(out, body.length);
        out.write(body);
        out.flush();
    }

    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    static int readVarInt(java.io.DataInput in) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new IOException("VarInt too long");
    }
}
