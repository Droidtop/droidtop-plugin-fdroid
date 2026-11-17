#!/usr/bin/env bash
# Run by the shared droidtop-plugin-bundle workflow in the build job, from this
# directory, after kotlinc is installed and before build.sh. Fetches the
# libraries this plugin bundles, each pinned by version AND by SHA-256 (a file
# that does not match is refused), and hands them to build.sh as
# FDROID_CLASSPATH:
#   - org.fdroid:index-android and org.fdroid:core-android, F-Droid's own index
#     library (gitlab.com/fdroid/fdroidclient libs/, Apache-2.0): the upstream
#     this plugin takes its F-Droid code from (Droidtop/tracker#261);
#   - kotlinx-serialization core and json, which that library parses with
#     (Apache-2.0), at the version it was built against.
# To take upstream fixes, bump a version here and its digest together.
set -euo pipefail
DEST="${RUNNER_TEMP:-$PWD/build}/fdroid-deps"
mkdir -p "$DEST"
M=https://repo1.maven.org/maven2
CP=""
fetch() {
  local path="$1" sha="$2"
  local file="$DEST/$(basename "$path")"
  curl -sfL "$M/$path" -o "$file"
  echo "$sha  $file" | sha256sum -c --quiet - || { echo "digest mismatch for $path" >&2; exit 1; }
  case "$file" in
    *.aar)
      local dir="${file%.aar}"
      mkdir -p "$dir"
      unzip -q -o "$file" classes.jar -d "$dir"
      CP="${CP:+$CP:}$dir/classes.jar"
      ;;
    *) CP="${CP:+$CP:}$file" ;;
  esac
}
fetch org/fdroid/index-android/0.2.0/index-android-0.2.0.aar 5dc874ee0d2fbdfa5902621d34697d309e14e6da573973e9a75bb7da5b523e42
fetch org/fdroid/core-android/0.0.1/core-android-0.0.1.aar f65da2168033e1d6daf67de3722f69def499bc421493830d2d83fed591dcf812
fetch org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.9.0/kotlinx-serialization-core-jvm-1.9.0.jar 1f0afa172110e45a7231ef1b44ae8fd84c1ebaff96f3fc3ad68ef8c48120b59c
fetch org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.9.0/kotlinx-serialization-json-jvm-1.9.0.jar d94cc34cae39246a1af74fda63f9c4812ce12216ef641d5fa3bbbb539a6922d8
if [ -n "${GITHUB_ENV:-}" ]; then
  echo "FDROID_CLASSPATH=$CP" >> "$GITHUB_ENV"
else
  echo "export FDROID_CLASSPATH=$CP"
fi
