#!/usr/bin/env bash
# No window; small CUDA correctness test, not a render/worldgen benchmark.
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$project_dir"
./gradlew classes -x compileCuda
test_classes=$(mktemp -d /tmp/pyridium-shader-test.XXXXXX)
javac -cp build/classes/java/main -d "$test_classes" tools/ShaderBatchCheck.java
java --enable-native-access=ALL-UNNAMED \
    -cp "$test_classes:build/classes/java/main:build/resources/main" \
    simon.pyridium.worldgen.exact.ShaderBatchCheck
