# keyset.json: root-signed working keys (key rotation without reinstall)

Owner procedure, incidents, rehearsal and migration: `docs/plans/KEY_ROTATION.md`. Code: Java `com.azur.launcher.keys`
(`Keyset`, `KeysetService`, `KeyTrust`, `RootKeys`), Go `tools/updater/internal/keyset` + `azur-servers keygen|keyset`.
Both sides implement the same rules; `KeysetInteropTest` runs the real Go tools against the Java verifiers.

## Trust chain
- The jar embeds N root public keys, `root_keys.txt` (we ship THREE: root A = working, roots B and C = emergency, kept in
  three different places). Filled by `packaging/release.sh` from `AZUR_ROOT_KEYS_FILE`; the tracked file is empty = dev
  build. Roots sign only the keyset.
- The keyset lists working keys: `servers` (servers.json, feed.json), `pack` (pack manifest), `launcher` (launcher.json),
  and delegated roots: `root` (a replacement working root A') and `root_emergency` (a replacement emergency root).
- Served at `https://<site>/dl/keyset.json` with `Cache-Control: no-cache, no-store` (Caddy). Cached verified copy:
  `<data>/keyset.json` (outside `cache/`).

## root_keys.txt
```
# comments and blank lines are ignored
working <base64 32-byte Ed25519 key>      # A
emergency <base64>                        # B
emergency <base64>                        # C
```
Old names `primary` (= working) and `reserve` (= emergency) still parse, so a two-line file is valid. A bare `<base64>`
line is the working root when it is the first key line, an emergency root otherwise. Rules: at least one working and
one emergency root, all keys different (2 roots minimum, 3 recommended; `release.sh` warns below 3).

## Envelope and payload
```json
{"payload": "<base64 of the payload bytes>", "sigs": [{"key_id": "<hex16>", "sig": "<base64 Ed25519 over the payload bytes>"}],
 "anchors": [{"payload": "...", "sigs": [...]}]}
```
```json
{"type":"azur-keyset/1","epoch":0,"version":7,"expires_at":"2026-12-20T00:00:00Z",
 "keys":[{"id":"launcher-20261105-1a2b3c","role":"launcher","key":"<base64 32 bytes>","expires_at":"2026-12-05T00:00:00Z"}],
 "revoked":["<key id or base64 key>"]}
```
`key_id` = hex of the first 8 bytes of SHA-256 of the raw key (informational; verifiers try every trusted key).
The signature covers the decoded payload bytes as they are (no re-serialisation).
`anchors` (optional, oldest first, at most 16, no nesting) = the emergency-signed keysets since the last one signed by an
embedded emergency root. The tool keeps them in `keyset.anchors.json` next to the source and embeds them in every
later keyset.

## Acceptance rules (launcher, `Keyset.open`; tool, `keyset.Open`)
0. Anchors: each anchor newer (epoch, version) than the launcher's keyset is verified in order with the rules below
   (its own expiry is ignored) and must be signed by an emergency root; it then becomes the previous keyset. This is
   how a fresh install or a launcher that missed an emergency keyset learns a delegated root (A', B').
1. Signers: emergency = embedded emergency roots + `root_emergency` keys of the previous keyset; working = embedded
   working roots + `root` keys of the previous keyset. A signer revoked by the previous keyset OR by the document itself
   does not count (a root never signs its own revocation).
2. Signed by an emergency signer: may change everything (epoch, delegated roots, root revocations, including revoking
   another emergency root). Signed only by a working signer: epoch and the root section (delegated root keys + root
   revocations) must stay as in the previous keyset (none and epoch 0 on first contact), otherwise `NEEDS_EMERGENCY`.
3. Anti-rollback: `(epoch, version)` never goes down; the same pair must have the same payload bytes.
4. Root revocations are cumulative; at least one usable emergency root (embedded and not revoked, or a usable
   `root_emergency` key) must remain; embedded roots never appear in `keys`.
5. One public key appears at most once (one key, one role). Unknown roles are refused.
6. `expires_at` of the keyset is in the future; a key is usable while listed, not revoked (by id, base64 or key id)
   and before its own `expires_at`.
Not implemented (possible later): a threshold, e.g. an epoch change needing signatures of 2 of the 3 roots.

## Launcher modes (`KeyTrust.Mode`)
| Mode | When | servers.json / feed | pack manifest | launcher.json |
|---|---|---|---|---|
| KEYSET | valid unexpired keyset | `servers` keys | `pack` keys (servers.json `pack.key` ignored) | one `signatures[]` entry by a `launcher` key |
| TRANSITION | roots embedded, no keyset ever accepted, version < `KeysetService.LEGACY_CUTOVER` (1.1.0) | embedded `servers_pubkey.txt` | `pack.key` of servers.json | refused |
| LEGACY | jar without roots (dev) | embedded key | `pack.key` | legacy `signature` |
| LOCKED | roots embedded and the keyset expired / cache broken / cutover passed | nothing | nothing | refused |

Refresh: first use after start (Home before the network server list, feed timer, update check, Play pack sync), then at
most every `KeysetService.MAX_AGE` (10 min); the feed timer (15 min) keeps an idle open launcher fresh. A refused or
unreachable keyset keeps the current one (logged `Keyset refused (<reason>)`; accepted ones log `(emergency root)` /
`(via anchors)`).

## launcher.json
`signature` (legacy, `servers.key`, read by launchers up to 1.0.6) and `signatures: [{key_id, sig}]` over the same
fixed message (`UpdateManifest.signedMessage`). Versions only go up (`UpdateService.check`, bootstrap `UpdateStore.select`).

## Tools (`azur-servers`)
`keygen --role root_working|root_emergency|servers|pack|launcher`; `keyset new|add-key|renew|revoke|compromised|sign`
(sign checks the result as a launcher with `--prev` AND as a fresh install); `keyset emergency --root-key B_or_C.key
--revoke-root X.pub --add-root A2.pub --add-emergency B2.pub` (always a new epoch); `keyset verify`; `keyset rehearse`
(owner's real keys, in memory, prints key -> ok/FAIL); `verify|verify-feed|verify-launcher|verify-pack --keyset --roots`.
