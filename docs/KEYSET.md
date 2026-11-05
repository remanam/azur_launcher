# keyset.json: root-signed working keys (key rotation without reinstall)

Owner procedure, incidents and migration: `docs/plans/KEY_ROTATION.md`. Code: Java `com.azur.launcher.keys`
(`Keyset`, `KeysetService`, `KeyTrust`, `RootKeys`), Go `tools/updater/internal/keyset` + `azur-servers keygen|keyset`.
Both sides implement the same rules; `KeysetInteropTest` runs the real Go tool against the Java verifier.

## Trust chain
- The jar embeds TWO root public keys, `root_keys.txt` (`primary <base64>`, `reserve <base64>`; filled by
  `packaging/release.sh` from `AZUR_ROOT_KEYS_FILE`; the tracked file is empty = dev build). Roots sign only the keyset.
- The keyset lists working keys: `servers` (servers.json, feed.json), `pack` (pack manifest), `launcher` (launcher.json),
  `root` (a replacement primary root, only the reserve may add it).
- Served at `https://<site>/dl/keyset.json` with `Cache-Control: no-cache, no-store` (Caddy). Cached verified copy:
  `<data>/keyset.json` (outside `cache/`).

## Envelope and payload
```json
{"payload": "<base64 of the payload bytes>", "sigs": [{"key_id": "<hex16>", "sig": "<base64 Ed25519 over the payload bytes>"}]}
```
```json
{"type":"azur-keyset/1","epoch":0,"version":7,"expires_at":"2026-12-20T00:00:00Z",
 "keys":[{"id":"launcher-20261105-1a2b3c","role":"launcher","key":"<base64 32 bytes>","expires_at":"2026-12-05T00:00:00Z"}],
 "revoked":["<key id or base64 key>"]}
```
`key_id` = hex of the first 8 bytes of SHA-256 of the raw key (informational; verifiers try every trusted key).
The signature covers the decoded payload bytes as they are (no re-serialisation).

## Acceptance rules (launcher, `Keyset.open`; tool, `keyset.Open`)
1. Signed by the reserve root: may change everything. Signed by the primary root (unless the previous keyset revoked it)
   or by a `root` key of the previous keyset: epoch and the root section (root-role keys + root revocations) must stay
   as in the previous keyset (none and epoch 0 on first contact), otherwise `NEEDS_RESERVE`.
2. Anti-rollback: `(epoch, version)` never goes down; the same pair must have the same payload bytes.
3. Root revocations are cumulative; the reserve cannot be revoked; embedded roots never appear as working keys.
4. One public key appears at most once (one key, one role). Unknown roles are refused.
5. `expires_at` of the keyset is in the future; a key is usable while listed, not revoked (by id, base64 or key id)
   and before its own `expires_at`.

## Launcher modes (`KeyTrust.Mode`)
| Mode | When | servers.json / feed | pack manifest | launcher.json |
|---|---|---|---|---|
| KEYSET | valid unexpired keyset | `servers` keys | `pack` keys (servers.json `pack.key` ignored) | one `signatures[]` entry by a `launcher` key |
| TRANSITION | roots embedded, no keyset ever accepted, version < `KeysetService.LEGACY_CUTOVER` (1.1.0) | embedded `servers_pubkey.txt` | `pack.key` of servers.json | refused |
| LEGACY | jar without roots (dev) | embedded key | `pack.key` | legacy `signature` |
| LOCKED | roots embedded and the keyset expired / cache broken / cutover passed | nothing | nothing | refused |

Refresh: first use after start (Home before the network server list, feed timer, update check, Play pack sync), then at
most every `KeysetService.MAX_AGE` (10 min); the feed timer (15 min) keeps an idle open launcher fresh. A refused or
unreachable keyset keeps the current one (logged `Keyset refused (<reason>)`).

## launcher.json
`signature` (legacy, `servers.key`, read by launchers up to 1.0.6) and `signatures: [{key_id, sig}]` over the same
fixed message (`UpdateManifest.signedMessage`). Versions only go up (`UpdateService.check`, bootstrap `UpdateStore.select`).
