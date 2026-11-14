# Branding a build

The public tree is generic: no built-in server, no servers.json address, no site or privacy link. A fork or a
distributor supplies its own values at build time with an optional, untracked classpath file
`src/main/resources/branding.properties` (listed in `.gitignore`). Without it the launcher still starts; the server
list then comes from the user setting (`pack.json` `serversUrl`) and the cache.

```properties
servers_url=https://example.org/servers.json   # signed list, used when the user setting is empty (release builds)
site_url=https://example.org                    # opened from the "launcher update" chip
privacy_url=https://example.org/privacy         # fallback when servers_url has no host
# optional built-in server, used only when the signed list cannot be loaded and there is no cache
builtin.id=example
builtin.name=Example server
builtin.address=play.example.org
builtin.port=25565
builtin.mc=1.21.1
builtin.neoforge=21.1.264
builtin.java=21
builtin.channel=stable
builtin.pack_url=https://example.org/pack
builtin.pack_key=<base64 public key of the pack signer>
builtin.motd=Short description
builtin.mirror=https://example.org/mirror
```

Installer vendor name: `AZUR_VENDOR=... packaging/release.sh`. The signed server list and pack protocol are
described in [SERVERS_JSON.md](SERVERS_JSON.md); keys in [KEYSET.md](KEYSET.md).
