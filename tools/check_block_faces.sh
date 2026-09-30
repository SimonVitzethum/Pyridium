#!/usr/bin/env bash
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
test_dir=$(mktemp -d /tmp/pyridium-face-test.XXXXXX)
javac -d "$test_dir" "$project_dir/src/client/java/simon/pyridium/client/world/FaceRaster.java" "$project_dir/tools/FaceRasterCheck.java"
java -cp "$test_dir" simon.pyridium.client.world.FaceRasterCheck
