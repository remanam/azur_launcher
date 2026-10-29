#!/bin/sh
# One command for the RELEASE installer of this OS: guarded jar, staged inputs, jpackage, guard over the image.
# Usage: packaging/release.sh [type ...]    (default: app-image + dmg on macOS, app-image + deb on Linux)
# Maven proxy only through the environment (MAVEN_OPTS / HTTPS_PROXY), never in files. Add -o via MVN_FLAGS=-o when offline.
set -eu
cd "$(dirname "$0")/.."
MVN=${MVN:-mvn}
VERSION=$(sed -n 's:^    <version>\(.*\)</version>$:\1:p' pom.xml | head -1)
"$MVN" ${MVN_FLAGS:-} -Pdist clean package
sh packaging/jpackage.sh "$VERSION" "$@"
"$MVN" ${MVN_FLAGS:-} -Pdist surefire:test@image-guard
echo "Release artifacts: $(pwd)/target/dist/out (UNSIGNED)"
