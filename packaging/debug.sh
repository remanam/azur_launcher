#!/bin/sh
# Owner-only DEBUG build (offline account, red banner). Never ship. Output: dist-debug/azur-launcher-debug.jar (+ run.sh).
# Builds in target-debug/ and writes to dist-debug/: release.sh (`mvn clean` of target/, dist-release/) never touches them and vice versa.
set -eu
cd "$(dirname "$0")/.."
MVN=${MVN:-mvn}
"$MVN" ${MVN_FLAGS:-} -Pdebug,fat -DskipTests clean package
rm -rf dist-debug
mkdir -p dist-debug
cp target-debug/azur-launcher-all.jar dist-debug/azur-launcher-debug.jar
printf '#!/bin/sh\ncd "$(dirname "$0")"\nexec java -jar azur-launcher-debug.jar "$@"\n' > dist-debug/run.sh
chmod +x dist-debug/run.sh
echo "Debug build: $(pwd)/dist-debug/run.sh"
