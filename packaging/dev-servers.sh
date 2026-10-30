#!/bin/sh
# DEV ONLY (never shipped: lives outside src/, not in any jar). Gives the launcher one local server entry for development.
# Makes a throwaway Ed25519 key, signs a servers.json with one 127.0.0.1 entry, installs it as <data>/cache/servers.json
# and the matching public key as <data>/servers_pubkey.txt (the documented override of the embedded key).
# Same data format as production. Usage: packaging/dev-servers.sh [--serve]   (AZUR_HOME=<dir> = data dir of the dev run)
set -eu
cd "$(dirname "$0")/.."
ROOT=$(cd .. && pwd)
if [ -n "${AZUR_HOME:-}" ]; then DATA=$AZUR_HOME
elif [ "$(uname)" = Darwin ]; then DATA="$HOME/Library/Application Support/AzurLauncher"
else DATA="${XDG_DATA_HOME:-$HOME/.local/share}/azur-launcher"; fi
PORT=${PORT:-8780}
DEV="$DATA/dev"
mkdir -p "$DEV" "$DATA/cache"
BIN="$DEV/bin"; mkdir -p "$BIN"
(cd "$ROOT/tools/updater" && go build -trimpath -o "$BIN/azur-servers" ./cmd/azur-servers && go build -trimpath -o "$BIN/azur-dist" ./cmd/azur-dist)
KEY="$DEV/dev-signing.key"
if [ ! -f "$KEY" ]; then (umask 077; head -c 32 /dev/urandom | base64 > "$KEY"); fi
MC=$(sed -n 's/.*"minecraft": *"\([^"]*\)".*/\1/p' src/main/resources/profile.json)
NF=$(sed -n 's/.*"neoforge": *"\([^"]*\)".*/\1/p' src/main/resources/profile.json)
cat > "$DEV/servers.json" <<JSON
{"schema":1,"validUntil":"2099-01-01T00:00:00Z","servers":[{"id":"dev-local","name":"Dev local server",
"address":"127.0.0.1","port":25565,"mcVersion":"$MC","neoforge":"$NF","java":"21","packChannel":"stable",
"packUrl":"http://127.0.0.1:$PORT","inviteRequired":false,"motd":"Local test entry (dev-servers.sh)"}]}
JSON
"$BIN/azur-servers" sign --key "$KEY" --in "$DEV/servers.json" --out "$DATA/cache/servers.json" --valid-days 30
"$BIN/azur-servers" pubkey --key "$KEY" > "$DATA/servers_pubkey.txt"
"$BIN/azur-servers" verify --in "$DATA/cache/servers.json" --pub "$(cat "$DATA/servers_pubkey.txt")"
echo "Installed into: $DATA (Settings: leave the servers URL empty)"
if [ "${1:-}" = "--serve" ]; then
  echo "Pack server on 127.0.0.1:$PORT (Ctrl+C to stop; publish a pack first: $BIN/azur-dist publish --data $DEV/dist-data ...)"
  exec "$BIN/azur-dist" serve --data "$DEV/dist-data" --listen "127.0.0.1:$PORT"
fi
