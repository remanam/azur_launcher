#!/bin/sh
# One command for the RELEASE of this OS: Gradle build, jpackage (optional), then the copy into dist-release/.
# Usage: packaging/release.sh [type ...]    (default: app-image + dmg on macOS, app-image + deb on Linux)
# Extra Gradle flags: GRADLE_FLAGS="--offline --no-daemon". Proxy only through the environment, never in files.
set -eu
cd "$(dirname "$0")/.."
VERSION=$(sed -n "s/^version = '\(.*\)'$/\1/p" build.gradle | head -1)
STAGE=build/dist

# Offline by default with the repo-wide Gradle home; GRADLE_ONLINE=1 (plus a proxy in GRADLE_OPTS) for new dependencies.
if [ "${GRADLE_ONLINE:-0}" != "1" ]; then
  GRADLE_USER_HOME="${GRADLE_USER_HOME:-$(cd .. && pwd)/.gradle-home}"
  export GRADLE_USER_HOME
  GRADLE_FLAGS="--offline ${GRADLE_FLAGS:-}"
fi

# Production servers.json key: AZUR_SERVERS_PUBKEY_FILE=<file with the base64 public key> (see tools/deploy/keys/gen_keys.sh).
# It replaces the embedded dev key only for this build; the tracked resource is restored on exit, so no key is hardcoded in git.
PUBRES=src/main/resources/servers_pubkey.txt
if [ -n "${AZUR_SERVERS_PUBKEY_FILE:-}" ]; then
  [ -f "$AZUR_SERVERS_PUBKEY_FILE" ] || { echo "AZUR_SERVERS_PUBKEY_FILE not found: $AZUR_SERVERS_PUBKEY_FILE" >&2; exit 1; }
  PUBKEY=$(tr -d '[:space:]' < "$AZUR_SERVERS_PUBKEY_FILE")
  [ "${#PUBKEY}" -eq 44 ] || { echo "public key must be 44 base64 chars (32 raw bytes)" >&2; exit 1; }
  cp "$PUBRES" "$PUBRES.orig"
  trap 'mv -f "$PUBRES.orig" "$PUBRES"' EXIT INT TERM
  printf '%s\n' "$PUBKEY" > "$PUBRES"
  echo "embedding production servers public key from $AZUR_SERVERS_PUBKEY_FILE"
fi

./gradlew ${GRADLE_FLAGS:-} --quiet clean releaseJar

# jpackage input: the fat jar (it already carries JavaFX for every platform).
rm -rf "$STAGE"
mkdir -p "$STAGE/input"
cp build/libs/azur-launcher-all.jar "$STAGE/input/azur-launcher.jar"

sh packaging/jpackage.sh "$VERSION" "$@"

rm -rf dist-release
mkdir -p dist-release
cp build/libs/azur-launcher-all.jar dist-release/azur-launcher-beta.jar
mkdir -p ../launcher_builds
cp build/libs/azur-launcher-all.jar ../launcher_builds/azur-launcher-beta.jar
cp -R "$STAGE/out/." dist-release/
cp dist-release/azur-launcher-beta.jar ../launcher_builds/azur-launcher-beta.jar
echo "Release artifacts: $(pwd)/dist-release (UNSIGNED installers; the jar runs on any OS with Java 21)"
