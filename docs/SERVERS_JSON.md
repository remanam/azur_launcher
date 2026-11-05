# servers.json: the signed server list

The launcher shows the servers from one signed file. It is fetched over https, verified with an embedded
Ed25519 public key, cached on disk and used offline as the last known good copy.

## File format (the envelope)
```json
{"payload": "<base64 of the canonical JSON below>", "sig": "<base64 Ed25519 signature of exactly those bytes>"}
```
The signature covers the decoded payload bytes as they are, so the verifier never re-serialises JSON.
The signer (`azur-servers sign`) writes the payload canonical: object keys sorted, no whitespace, UTF-8.

## Payload
```json
{
  "schema": 2,
  "validUntil": "2026-12-01T00:00:00Z",
  "servers": [{
    "id": "isles",                 "name": "Azur Launcher",
    "address": "play.example.org", "port": 25565,
    "mcVersion": "1.21.1",         "neoforge": "21.1.264",   "java": "21",
    "packChannel": "stable",       "motd": "Short description",
    "pack": {"url": "https://dist.example.org/pack", "key": "<base64 Ed25519 public key, 32 bytes>"},
    "mirror": "https://dist.example.org/mirror"
  }]
}
```
- Required: `schema` (=2; schema 1 with `packUrl`/`inviteRequired` is refused), `validUntil` (RFC 3339), `servers[]` with unique non-empty `id`, `name`, `address`.
- `port` 0 or absent = 25565; range 1..65535. Other fields are optional strings.
- `neoforge` selects the installed game version for the server (its id must contain the build); `neoforgeSha256` (optional): SHA-256 of the NeoForge installer when the build differs from the embedded profile; without it only the embedded build is accepted.
- `pack` (optional object): where the pack of this server is and which key signs its manifest. `url`: https (plain http only for 127.0.0.1 in local tests), no trailing path rules beyond a reverse-proxy prefix such as `/pack`; `key`: base64 of the 32 raw bytes (`azur-servers pubkey`). The pack download is open (no tokens). Without `pack` the launcher installs the game but no pack (error `nopack` on update); a wrong `key` makes the manifest check fail (`signature`). `azur-servers sign` rejects a malformed `pack`.
- `mirror` (optional): base URL of our own mirror of `maven.neoforged.net` and `libraries.minecraft.net` (layout `<mirror>/<host>/<path>`, e.g. `<mirror>/maven.neoforged.net/releases/net/neoforged/...`). The launcher tries it first for the NeoForge installer and its libraries and falls back to the official host; hashes are checked either way. https only (plain http only for 127.0.0.1 in local tests).
- Unknown fields are ignored. A different `schema` is refused.

## Launcher behaviour
1. Fetch `settings.servers_url` (https). Verify signature, then format, then `validUntil` against the system clock.
2. On success write `<data>/cache/servers.json` (atomic). A file that fails any check is never cached.
3. On network error or a rejected download use the cache: valid = silent, expired = shown with a "stale" banner
   (correct signature required), bad signature = ignored.
4. Keys: with a keyset (`docs/KEYSET.md`, jars with root keys) the signature must come from a `servers` key of the
   keyset and the `pack.key` of an entry is ignored (manifest keys come from the keyset). Without one (TRANSITION/LEGACY):
   `servers_pubkey.txt` in the jar (base64 of the 32 raw bytes; `<data>/servers_pubkey.txt` overrides it in a dev run)
   and the entry's `pack.key`. Keep `pack.key` in the published list while launchers up to 1.0.6 exist.

## Signing (owner)
Same key file as `azur-dist` (`~/.azur-dist/signing.key`, base64 seed):
```
azur-servers pubkey                                   # print the public key to embed
azur-servers sign --in servers.json --out servers.signed.json --valid-days 30
azur-servers verify --in servers.signed.json --pub <base64 key>
```
Publish `servers.signed.json` at the URL players have in their settings. Source: `tools/updater/cmd/azur-servers`.
