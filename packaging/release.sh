#!/bin/sh
# One command for the RELEASE of this OS: Gradle build, jpackage (optional), then the copy into dist-release/.
# Usage: packaging/release.sh [type ...]    (default: app-image + dmg on macOS, app-image + deb on Linux)
# Extra Gradle flags: GRADLE_FLAGS="--offline --no-daemon". Proxy only through the environment, never in files.
set -eu
cd "$(dirname "$0")/.."
VERSION=$(sed -n "s/^version = '\(.*\)'$/\1/p" build.gradle | head -1)
STAGE=build/dist

# GRADLE_FLAGS may carry extra Gradle options (for example --offline).

# Production servers.json key: AZUR_SERVERS_PUBKEY_FILE=<file with the base64 public key>.
# It replaces the embedded dev key only for this build; the tracked resource is restored on exit, so no key is hardcoded in git.
PUBRES=src/main/resources/servers_pubkey.txt
if [ -n "${AZUR_SERVERS_PUBKEY_FILE:-}" ]; then
  [ -f "$AZUR_SERVERS_PUBKEY_FILE" ] || { echo "AZUR_SERVERS_PUBKEY_FILE not found: $AZUR_SERVERS_PUBKEY_FILE" >&2; exit 1; }
  PUBKEY=$(tr -d '[:space:]' < "$AZUR_SERVERS_PUBKEY_FILE")
  [ "${#PUBKEY}" -eq 44 ] || { echo "public key must be 44 base64 chars (32 raw bytes)" >&2; exit 1; }
  cp "$PUBRES" "$PUBRES.orig"
  printf '%s\n' "$PUBKEY" > "$PUBRES"
  echo "embedding production servers public key from $AZUR_SERVERS_PUBKEY_FILE"
fi

# Root keys of the keyset (docs/KEYSET.md): AZUR_ROOT_KEYS_FILE=<root_keys.txt, public keys
# only, one root per line: 'working <b64>' (A) and 'emergency <b64>' (B, C); old names primary/reserve and bare keys
# (first = working) also pass>. At least 2 roots with one working and one emergency; 3 are recommended (warning).
# A release without roots never leaves the single-key path and can never rotate keys, so it is refused unless
# AZUR_ALLOW_NO_ROOTS=1 (dev builds).
ROOTRES=src/main/resources/root_keys.txt
if [ -n "${AZUR_ROOT_KEYS_FILE:-}" ]; then
  [ -f "$AZUR_ROOT_KEYS_FILE" ] || { echo "AZUR_ROOT_KEYS_FILE not found: $AZUR_ROOT_KEYS_FILE" >&2; exit 1; }
  ROOTS_CHECK=$(awk '
    /^[[:space:]]*(#|$)/ { next }
    { role = ""; key = $1; if (NF == 2) { role = $1; key = $2 } else if (NF != 1) { bad = 1 }
      if (role == "") role = (n == 0 ? "working" : "emergency")
      if (role == "primary") role = "working"; if (role == "reserve") role = "emergency"
      if ((role != "working" && role != "emergency") || key !~ /^[A-Za-z0-9+\/]{43}=$/ || seen[key]++) bad = 1
      n++; if (role == "working") w++; else e++ }
    END { if (bad || n < 2 || !w || !e) print "bad"; else print n }' "$AZUR_ROOT_KEYS_FILE")
  [ "$ROOTS_CHECK" != bad ] || { echo "root keys file needs >= 2 different roots: 'working <base64>' and 'emergency <base64>' lines (3 recommended)" >&2; exit 1; }
  [ "$ROOTS_CHECK" -ge 3 ] || echo "WARNING: only $ROOTS_CHECK root keys; 3 are recommended (A working, B and C emergency in two places)" >&2
  cp "$ROOTRES" "$ROOTRES.orig"
  grep -Ev '^[[:space:]]*(#|$)' "$AZUR_ROOT_KEYS_FILE" > "$ROOTRES"
  echo "embedding $ROOTS_CHECK root public keys from $AZUR_ROOT_KEYS_FILE"
elif [ "${AZUR_ALLOW_NO_ROOTS:-0}" != "1" ]; then
  echo "AZUR_ROOT_KEYS_FILE is not set: a release needs the root keys (or AZUR_ALLOW_NO_ROOTS=1 for a dev build)" >&2
  exit 1
fi
restore_keys() {
  [ -f "$PUBRES.orig" ] && mv -f "$PUBRES.orig" "$PUBRES"
  [ -f "$ROOTRES.orig" ] && mv -f "$ROOTRES.orig" "$ROOTRES"
  return 0
}
trap restore_keys EXIT INT TERM

./gradlew ${GRADLE_FLAGS:-} --quiet clean releaseJar

# jpackage input: the fat jar (it already carries JavaFX for every platform).
rm -rf "$STAGE"
mkdir -p "$STAGE/input"
cp build/libs/azur-launcher-all.jar "$STAGE/input/azur-launcher.jar"

sh packaging/jpackage.sh "$VERSION" "$@"

rm -rf dist-release
mkdir -p dist-release
cp build/libs/azur-launcher-all.jar dist-release/azur-launcher-beta.jar
cp -R "$STAGE/out/." dist-release/
echo "Release artifacts: $(pwd)/dist-release (UNSIGNED installers; the jar runs on any OS with Java 21)"
