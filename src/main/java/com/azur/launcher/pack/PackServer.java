package com.azur.launcher.pack;

import com.azur.launcher.net.Hashes;
import com.azur.launcher.net.HostPolicy;
import com.azur.launcher.net.ProxyConfig;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * HTTP side of the pack client: status, signed manifest, content-addressed files. Every URL (the first and anything the
 * manifest references) must be on the pack server's own origin and pass the {@link HostPolicy}; redirects are refused.
 */
public final class PackServer {

    /** {@code /status}: maintenance flag and banner text set by the host. */
    public record Status(boolean maintenance, String message, @SerializedName("pack_version") String packVersion) {
    }

    private static final Gson GSON = new Gson();

    private final URI base;
    private final List<byte[]> keys;
    private final HostPolicy policy;
    private final HttpClient http = ProxyConfig.configure(HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15)))
            .build();

    public PackServer(PackSource source, HostPolicy policy) throws PackException {
        this(source, policy, Optional.empty());
    }

    /** @param trusted the pack keys of the keyset; empty = the key of the server entry (legacy) */
    public PackServer(PackSource source, HostPolicy policy, Optional<List<byte[]>> trusted) throws PackException {
        if (!source.present()) {
            throw new PackException(PackException.NO_PACK, "the server list gives no pack address or key for this server");
        }
        try {
            this.base = new URI(source.url().endsWith("/") ? source.url() : source.url() + "/");
        } catch (java.net.URISyntaxException e) {
            throw new PackException(PackException.NETWORK, "the pack server address is wrong", e);
        }
        if (base.getHost() == null) {
            throw new PackException(PackException.NETWORK, "the pack server address is wrong");
        }
        this.keys = trusted.isPresent() ? List.copyOf(trusted.get()) : List.of(source.publicKey());
        this.policy = policy;
        checkPolicy(base.toString());
    }

    private void checkPolicy(String url) throws PackException {
        try {
            policy.check(url);
        } catch (IOException e) {
            throw new PackException(PackException.NETWORK, "the pack server is not allowed: " + e.getMessage(), e);
        }
    }

    /** Keeps downloads on the pack server only. */
    URI resolve(String ref) throws PackException {
        URI u = base.resolve(ref.startsWith("/") ? ref.substring(1) : ref);
        if (!base.getScheme().equals(u.getScheme()) || !base.getAuthority().equals(u.getAuthority())) {
            throw new PackException(PackException.SIGNATURE, "the manifest points to another server");
        }
        checkPolicy(u.toString());
        return u;
    }

    private HttpResponse<InputStream> get(String ref, long from, Duration timeout) throws PackException {
        URI uri = resolve(ref);
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(timeout).GET();
        if (from > 0) {
            req.header("Range", "bytes=" + from + "-");
        }
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new PackException(PackException.NETWORK, "cannot reach the pack server", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PackException(PackException.NETWORK, "interrupted", e);
        }
        int code = resp.statusCode();
        if (code >= 300) {
            closeQuietly(resp.body());
            throw new PackException(PackException.NETWORK, "the pack server answered " + code);
        }
        return resp;
    }

    public Status status() throws PackException {
        return status(Duration.ofSeconds(15));
    }

    /** Fresh {@code /status} with its own timeout (the Play decision must not hang on a dead server). */
    public Status status(Duration timeout) throws PackException {
        try (InputStream in = get("status", 0, timeout).body()) {
            Status s = GSON.fromJson(new String(in.readNBytes(1 << 16), StandardCharsets.UTF_8), Status.class);
            return s == null ? new Status(false, "", "") : s;
        } catch (PackException e) {
            throw e;
        } catch (IOException | JsonSyntaxException e) {
            throw new PackException(PackException.NETWORK, "bad status answer", e);
        }
    }

    public PackManifest manifest() throws PackException {
        try (InputStream in = get("manifest.json", 0, Duration.ofSeconds(30)).body()) {
            return SignatureVerifier.open(in.readNBytes(SignatureVerifier.MAX_MANIFEST + 1), keys);
        } catch (IOException e) {
            if (e instanceof PackException pe) {
                throw pe;
            }
            throw new PackException(PackException.NETWORK, "cannot read the manifest", e);
        }
    }

    /**
     * Downloads {@code ref} to {@code dst} through {@code dst.part} (resuming), checks size and SHA-256, then moves it
     * atomically. A wrong hash deletes the part and retries once.
     */
    public void fetch(String ref, String sha256, long size, Path dst, LongConsumer onBytes) throws IOException {
        Files.createDirectories(dst.getParent());
        Path part = dst.resolveSibling(dst.getFileName() + ".part");
        for (int attempt = 0; attempt < 2; attempt++) {
            fetchOnce(ref, size, part, onBytes);
            if (sha256.equals(Hashes.sha256(part))) {
                move(part, dst);
                return;
            }
            Files.deleteIfExists(part);
        }
        throw new PackException(PackException.NETWORK, "the downloaded file does not match its hash: " + ref);
    }

    private void fetchOnce(String ref, long size, Path part, LongConsumer onBytes) throws IOException {
        long have = Files.isRegularFile(part) ? Files.size(part) : 0;
        if (have > size) {
            Files.delete(part);
            have = 0;
        }
        if (have == size && size > 0) {
            onBytes.accept(size);
            return;
        }
        HttpResponse<InputStream> resp = get(ref, have, Duration.ofMinutes(30));
        boolean resume = resp.statusCode() == 206 && have > 0;
        if (!resume) {
            have = 0;
        }
        long written = 0;
        try (InputStream in = resp.body();
                OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        resume ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
            onBytes.accept(have);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                written += n;
                if (have + written > size) {
                    break;
                }
                out.write(buf, 0, n);
                onBytes.accept(n);
            }
        } catch (IOException e) {
            throw new PackException(PackException.NETWORK, "download interrupted: run it again, it resumes", e);
        }
        if (have + written != size) {
            if (have + written > size) {
                Files.deleteIfExists(part);
            }
            throw new PackException(PackException.NETWORK, "the download was cut or the file has another size");
        }
    }

    static void move(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing to release
        }
    }
}
