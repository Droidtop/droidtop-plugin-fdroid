#!/usr/bin/env bash
# Runs EntryJar against the real entry.jar files in test/fixtures on the JVM (kotlinc and java on PATH).
# The build workflow does not run it; run it before changing EntryJar.kt.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
trap 'rm -rf -- "${OUT:?}"' EXIT
kotlinc -jvm-target 17 -d "$OUT" src/dev/droidtop/plugins/fdroid/EntryJar.kt test/EntryJarTest.kt
java -cp "$OUT:$(dirname "$(command -v kotlinc)")/../lib/kotlin-stdlib.jar" dev.droidtop.plugins.fdroid.EntryJarTestKt test/fixtures
