#!/usr/bin/env bash
# Disposable, windowless Full-HD renderer test. The source save is never opened.
set -euo pipefail

project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ $# != 1 ]]; then
    echo "Usage: bash tools/check_renderer.sh <Minecraft save directory>" >&2
    exit 2
fi
source_world=$(realpath -- "$1")
if [[ ! -f "$source_world/level.dat" ]]; then
    echo "Missing level.dat in source save: $source_world" >&2
    exit 2
fi
test_dir=$(mktemp -d /tmp/pyridium-render-check.XXXXXX)
mkdir -p -- "$test_dir/saves"
cp -a -- "$source_world" "$test_dir/saves/RenderTest"
if [[ -f "$project_dir/run/options.txt" ]]; then
    cp -- "$project_dir/run/options.txt" "$test_dir/options.txt"
fi
echo "Disposable test world and native Full-HD captures: $test_dir"
cd -- "$project_dir"
SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
PYRIDIUM_TEST_GPU_DUTY="${PYRIDIUM_TEST_GPU_DUTY:-1}" \
./gradlew runClient -PpydRunDir="$test_dir" -PquickWorld=RenderTest -PpydSize=1920x1080 \
    "-PpydJvm=-Dpyridium.headless=true -Dpyridium.noPrefetch=true -Dpyridium.gpuWorldgen=false -Dpyridium.mode=rr -Dpyridium.viewChunks=1024 -Dpyridium.cam=1000,182,1000,0,10 -Dpyridium.autoQuit=90 -Dpyridium.testTimeout=180 -Dpyridium.shotDir=$test_dir/shots -Dpyridium.shotEvery=15000 -Dpyridium.targetFps=0 -Dpyridium.testEntities=true -Dpyridium.testEntityFeatures=true" \
    -x compileCuda

test_log="$test_dir/logs/latest.log"
if ! rg -q 'Aufnahme .*\(1920x1080, Frame' "$test_log"; then
    echo "FAIL: no native Full-HD capture recorded" >&2
    exit 1
fi
if ! rg -q '[1-9][0-9]* Item- und [1-9][0-9]* Custom-Instanzen' "$test_log"; then
    echo "FAIL: item/custom fixtures were not rendered" >&2
    exit 1
fi
if rg -q 'Modell-DAG|Geometrie überschreitet|Pyridium: Weltframe fehlgeschlagen|Pyridium: Welt abgeschaltet|Pyridium: Aufnahme fehlgeschlagen|Pyridium: Zeitlimit des Headless-Tests erreicht' "$test_log"; then
    echo "FAIL: renderer geometry or capture error; inspect $test_log" >&2
    exit 1
fi
rg 'Item- und|Chunks sichtbar|PydSurface.*FPS|Aufnahme ' "$test_log" | tail -16
file -- "$test_dir"/shots/*.png
echo "PASS: Full-HD item/custom renderer smoke test. This does not validate GPU worldgen or 165 FPS."
