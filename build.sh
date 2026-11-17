#!/usr/bin/env bash
# Compiles, shrinks, dexes and hashes droidtop.fdroid's payload (manifest.json
# and classes.jar) in the shape droidtop's PluginBundleInstaller expects
# (droidtop docs/SPEC.md 12a). Never touches a signing key: sign.sh does that.
#
# The libraries the plugin bundles (prepare.sh, FDROID_CLASSPATH) go through R8
# and are moved under dev.droidtop.plugins.fdroid.lib: a plugin's class loader
# asks droidtop's first, and droidtop has its own kotlinx.serialization, so an
# unmoved copy could be answered by droidtop's instead. The plugin API
# (PLUGIN_HOST_CLASSPATH) and the Kotlin standard library come from droidtop and
# are not bundled.
#
# Prerequisites (the shared workflow provides them): kotlinc and d8 on PATH,
# PLUGIN_HOST_CLASSPATH, ANDROID_JAR, FDROID_CLASSPATH (prepare.sh).
set -euo pipefail
cd "$(dirname "$0")"

: "${PLUGIN_HOST_CLASSPATH:?set to a jar containing dev.droidtop.pluginhost.* compiled classes}"
: "${ANDROID_JAR:?set ANDROID_JAR to android.jar for the target compileSdk}"
: "${FDROID_CLASSPATH:?run prepare.sh first}"

rm -rf build/classes build/r8 build/classes.jar build/manifest.json
mkdir -p build/classes build/r8

kotlinc -jvm-target 17 -cp "$PLUGIN_HOST_CLASSPATH:$FDROID_CLASSPATH:$ANDROID_JAR" -d build/classes $(find src -name '*.kt')

KOTLIN_HOME="$(cd "$(dirname "$(command -v kotlinc)")/.." && pwd)"
R8_JAR="$(cd "$(dirname "$(command -v d8)")" && pwd)/lib/d8.jar"
test -f "$R8_JAR" || { echo "no R8 at $R8_JAR" >&2; exit 1; }

PROGRAM=( $(find build/classes -name '*.class') )
IFS=':' read -ra LIBS <<< "$FDROID_CLASSPATH"
java -cp "$R8_JAR" com.android.tools.r8.R8 --release --min-api 26 \
  --output build/r8 --pg-conf rules.pro \
  --lib "$ANDROID_JAR" \
  --classpath "$PLUGIN_HOST_CLASSPATH" --classpath "$KOTLIN_HOME/lib/kotlin-stdlib.jar" \
  "${PROGRAM[@]}" "${LIBS[@]}"

# classes.jar is a zip with classes.dex at its root, what the plugin loader reads.
(cd build/r8 && zip -q ../classes.jar classes*.dex)

CLASSES_SHA=$(sha256sum build/classes.jar | cut -d' ' -f1)
python3 - "$CLASSES_SHA" <<'PY'
import json, sys
manifest = json.load(open("manifest.template.json"))
manifest["payload"] = [{"path": "classes.jar", "sha256": sys.argv[1]}]
json.dump(manifest, open("build/manifest.json", "w"), indent=2, sort_keys=True)
PY

echo "Built build/classes.jar and build/manifest.json (unsigned)"
sha256sum build/classes.jar

if [ -n "${PLUGIN_SIGNING_KEY:-}" ]; then
  PLUGIN_SIGNING_KEY="$PLUGIN_SIGNING_KEY" ./sign.sh
fi
