# Community API contract (owner side)

The launcher talks to our own service for registration, roles, whitelist status and the pack invite. The **Minecraft
sign-in stays with Microsoft**: this API never replaces it and never issues a Minecraft session.

Client: [`CommunityApi`](../src/main/java/com/azur/launcher/api/CommunityApi.java).
Token storage: `accounts/player.vault` (same AES-GCM vault as the accounts, [`PlayerTokenStore`](../src/main/java/com/azur/launcher/auth/PlayerTokenStore.java)).
Transport: https only; plain http is accepted only for `127.0.0.1`/`localhost` (local development).

## Endpoints

### `POST /v1/register`

```json
{"code": "AB12-CD34", "uuid": "0123456789abcdef0123456789abcdef", "nick": "Vasya"}
```

`uuid` comes from the verified Microsoft session — the only stable identity (nicks change). `code` is issued by the
owner out of band and may be single-use or reusable, that is the API's decision.

| Status | Body |
|---|---|
| 200 | `{"player_token": "…", "nick": "Vasya", "roles": ["friend"]}` |
| 403 | `{"error": "code_invalid"}`, `code_used`, `code_expired` |
| 409 | `{"error": "already_registered"}` |

### `GET /v1/me`

Header `Authorization: Bearer <player_token>`.

```json
{
  "nick": "Vasya",
  "uuid": "0123456789abcdef0123456789abcdef",
  "roles": ["friend", "builder"],
  "whitelisted": true,
  "server": {"name": "Azur Launcher", "address": "mc.example.ru", "port": 25565},
  "pack": {"url": "https://pack.example.ru", "invite": "azur1.…", "version": "2026.10.29-1900"}
}
```

| Status | Body |
|---|---|
| 200 | as above; `server` and `pack` may be absent, missing fields are tolerated by the client |
| 401 | `{"error": "token_invalid"}` / `{"error": "token_revoked"}` |

All fields are optional for the client: a missing `pack.invite` simply means "no invite to apply".

### `POST /v1/token/rotate`

Header `Authorization: Bearer <player_token>`, body `{}`.

| Status | Body |
|---|---|
| 200 | `{"player_token": "…"}` — the old token must be invalid from now on |
| 401 | `{"error": "token_invalid"}` |

## What the launcher does with the answer

1. Stores `player_token` in the vault (never in `pack.json`, never in logs).
2. `GET /v1/me` right after registration; `pack.invite` is written to the invite vault and used for `azur-updater sync`.
3. `whitelisted=false` is shown to the player as "not added yet", not as an error.
4. `roles` are displayed only; no client-side permission depends on them.

## Security boundaries

- The API is **not** an access gate for the Minecraft server. A modified launcher can claim anything, so the server must
  keep `online-mode=true` and its own whitelist by UUID; the API's `whitelisted` flag is for the player's convenience.
- If the API is supposed to maintain the server whitelist, it does so server-side (RCON or the whitelist file), never
  through the launcher.
- Invite codes for the pack (`azur1.…`) are separate from `player_token`: the invite carries the pack address, its
  access token and the Ed25519 key that signs the manifest. Both can be issued by the same service.
