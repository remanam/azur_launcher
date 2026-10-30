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

./gradlew ${GRADLE_FLAGS:-} --quiet clean releaseJar

# jpackage input: the fat jar (it already carries JavaFX for every platform) plus the updater binaries of this OS.
rm -rf "$STAGE"
mkdir -p "$STAGE/input" "$STAGE/updater-src"
cp build/libs/azur-launcher-all.jar "$STAGE/input/azur-launcher.jar"
if ls ../tools/updater/dist-bin/azur-updater-* >/dev/null 2>&1; then
  cp ../tools/updater/dist-bin/azur-updater-* "$STAGE/updater-src/"
fi

sh packaging/jpackage.sh "$VERSION" "$@"

rm -rf dist-release
mkdir -p dist-release
cp build/libs/azur-launcher-all.jar dist-release/azur-launcher-beta.jar
mkdir -p ../launcher_builds
cp build/libs/azur-launcher-all.jar ../launcher_builds/azur-launcher-beta.jar
cp -R "$STAGE/out/." dist-release/
# the updater binaries belong next to the jar for the installer-free distribution
if [ -d build/dist/updater-src ]; then
  mkdir -p dist-release/updater && cp build/dist/updater-src/azur-updater-* dist-release/updater/ 2>/dev/null || true
fi
mkdir -p ../launcher_builds/updater
cp dist-release/azur-launcher-beta.jar ../launcher_builds/azur-launcher-beta.jar
if [ -d dist-release/updater ]; then cp dist-release/updater/* ../launcher_builds/updater/; fi
echo "Release artifacts: $(pwd)/dist-release (UNSIGNED installers; the jar runs on any OS with Java 21)"
