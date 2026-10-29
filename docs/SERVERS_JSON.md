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
  "schema": 1,
  "validUntil": "2026-12-01T00:00:00Z",
  "servers": [{
    "id": "isles",                 "name": "Azur Launcher",
    "address": "play.example.org", "port": 25565,
    "mcVersion": "1.21.1",         "neoforge": "21.1.264",   "java": "21",
    "packChannel": "stable",       "packUrl": "https://dist.example.org:8780",
    "inviteRequired": true,        "motd": "Short description"
  }]
}
```
- Required: `schema` (=1), `validUntil` (RFC 3339), `servers[]` with unique non-empty `id`, `name`, `address`.
- `port` 0 or absent = 25565; range 1..65535. Other fields are optional strings (`inviteRequired` a boolean).
- `neoforge` selects the installed game version for the server (its id must contain the build); `packUrl`, when set,
- `neoforgeSha256` (optional): SHA-256 of the NeoForge installer when the build differs from the embedded profile; without it only the embedded build is accepted.
  replaces the address stored in the player's invite code.
- Unknown fields are ignored. A different `schema` is refused.

## Launcher behaviour
1. Fetch `settings.servers_url` (https). Verify signature, then format, then `validUntil` against the system clock.
2. On success write `<data>/cache/servers.json` (atomic). A file that fails any check is never cached.
3. On network error or a rejected download use the cache: valid = silent, expired = shown with a "stale" banner
   (correct signature required), bad signature = ignored.
4. Public key: `servers_pubkey.txt` in the jar (base64 of the 32 raw bytes); a file `<data>/servers_pubkey.txt` overrides it.
   Replace the embedded dev key with the production key (`azur-servers pubkey`) before a release.

## Signing (owner)
Same key file as `azur-dist` (`~/.azur-dist/signing.key`, base64 seed):
```
azur-servers pubkey                                   # print the public key to embed
azur-servers sign --in servers.json --out servers.signed.json --valid-days 30
azur-servers verify --in servers.signed.json --pub <base64 key>
```
Publish `servers.signed.json` at the URL players have in their settings. Source: `tools/updater/cmd/azur-servers`.
