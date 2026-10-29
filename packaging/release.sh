#!/bin/sh
# One command for the RELEASE of this OS: cross-platform jar (primary), guarded jar, staged inputs, jpackage (optional), guard over the image.
# Usage: packaging/release.sh [type ...]    (default: app-image + dmg on macOS, app-image + deb on Linux)
# Maven proxy only through the environment (MAVEN_OPTS / HTTPS_PROXY), never in files. Add -o via MVN_FLAGS=-o when offline.
set -eu
cd "$(dirname "$0")/.."
MVN=${MVN:-mvn}
VERSION=$(sed -n 's:^    <version>\(.*\)</version>$:\1:p' pom.xml | head -1)
# `clean` removes only target/ (release); the debug build lives in target-debug/ and dist-debug/, release outputs in dist-release/
"$MVN" ${MVN_FLAGS:-} -Pdist,fat clean package
sh packaging/jpackage.sh "$VERSION" "$@"
"$MVN" ${MVN_FLAGS:-} -Pdist surefire:test@image-guard
rm -rf dist-release
mkdir -p dist-release
cp target/azur-launcher-all.jar "dist-release/azur-launcher-$VERSION.jar"
cp -R target/dist/out/. dist-release/
echo "Release artifacts: $(pwd)/dist-release (UNSIGNED installers; the jar runs on any OS with Java 21)"
