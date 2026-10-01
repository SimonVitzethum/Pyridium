#!/usr/bin/env bash
# Disposable, windowless Full-HD renderer test. The source save is never opened.
set -euo pipefail

case "${PYRIDIUM_TEST_LIGHTING:-path}" in
    path|direct) ;;
    *) echo "PYRIDIUM_TEST_LIGHTING must be path or direct" >&2; exit 2 ;;
esac
for feature in RR RAY_EFFECTS PATH_TRACING HARDWARE_RT; do
    variable="PYRIDIUM_TEST_$feature"
    value="${!variable:-true}"
    case "$value" in true|false) ;; *) echo "$variable must be true or false" >&2; exit 2 ;; esac
done

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
if [[ -n "${PYRIDIUM_TEST_PACK:-}" ]]; then
    test_pack=$(realpath -- "$PYRIDIUM_TEST_PACK")
    [[ -f "$test_pack" ]] || { echo "Missing test resource pack" >&2; exit 2; }
    mkdir -p -- "$test_dir/resourcepacks"
    cp -- "$test_pack" "$test_dir/resourcepacks/Pyridium-PBR-Test.zip"
    # This modifies ONLY the disposable copy, never the player's options.
    sed -i 's/^resourcePacks:.*/resourcePacks:["vanilla","file\/Pyridium-PBR-Test.zip"]/' "$test_dir/options.txt"
fi
echo "Disposable test world and native Full-HD captures: $test_dir"
extra_jvm="-Dpyridium.rayReconstruction=${PYRIDIUM_TEST_RR:-true} -Dpyridium.rayEffects=${PYRIDIUM_TEST_RAY_EFFECTS:-true} -Dpyridium.pathTracing=${PYRIDIUM_TEST_PATH_TRACING:-true} -Dpyridium.hardwareRaytracing=${PYRIDIUM_TEST_HARDWARE_RT:-true}"
if [[ "${PYRIDIUM_TEST_TOGGLES:-0}" == 1 ]]; then
    extra_jvm="$extra_jvm -Dpyridium.testRendererToggles=true -Dpyridium.testVideoSettings=true"
fi
if [[ "${PYRIDIUM_TEST_SPECTATOR:-0}" == 1 ]]; then
    extra_jvm="$extra_jvm -Dpyridium.testSpectator=true -Dpyridium.testVideoSettings=true"
    if [[ "${PYRIDIUM_TEST_SPECTATOR_PREBUILD:-1}" == 1 ]]; then
        extra_jvm="$extra_jvm -Dpyridium.spectatorCache=true"
    fi
fi
cd -- "$project_dir"
SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
PYRIDIUM_TEST_GPU_DUTY="${PYRIDIUM_TEST_GPU_DUTY:-1}" \
./gradlew runClient -PpydRunDir="$test_dir" -PquickWorld=RenderTest -PpydSize=1920x1080 \
    "-PpydJvm=-Dpyridium.headless=true -Dpyridium.noPrefetch=true -Dpyridium.gpuWorldgen=false -Dpyridium.mode=rr -Dpyridium.viewChunks=1024 -Dpyridium.autoQuit=${PYRIDIUM_TEST_SECONDS:-90} -Dpyridium.testTimeout=${PYRIDIUM_TEST_TIMEOUT:-240} -Dpyridium.shotDir=$test_dir/shots -Dpyridium.shotEvery=15000 -Dpyridium.targetFps=0 -Dpyridium.testEntities=true -Dpyridium.testEntityFeatures=true -Dpyridium.testMaterials=true $extra_jvm" \
    -x compileCuda 2>&1 | tee "$test_dir/native.log"

test_log="$test_dir/logs/latest.log"
if [[ "${PYRIDIUM_TEST_TOGGLES:-0}" == 1 ]]; then
    for expected in 'RR false, GI true, RT-Effekte true, RT-Cores true' \
                    'RR false, GI false, RT-Effekte true, RT-Cores true' \
                    'RR false, GI false, RT-Effekte false, RT-Cores true' \
                    'RR false, GI false, RT-Effekte false, RT-Cores false'; do
        if ! rg -q "Renderer-Features: $expected" "$test_dir/native.log"; then
            echo "FAIL: live renderer transition missing: $expected" >&2; exit 1
        fi
    done
    if ! rg -q 'Renderer-Schalter wiederhergestellt' "$test_log" ||
       ! rg -q 'Grafikeinstellungen fensterlos geoeffnet' "$test_log"; then
        echo "FAIL: renderer settings lifecycle did not finish" >&2; exit 1
    fi
    for key in rayReconstruction rayEffects pathTracing hardwareRaytracing; do
        if ! rg -q "^$key=true$" "$test_dir/config/pyridium-renderer.properties"; then
            echo "FAIL: renderer setting $key was not persisted" >&2; exit 1
        fi
    done
fi
if ! rg -q "Renderer-Features: RR ${PYRIDIUM_TEST_RR:-true}, .*RT-Effekte ${PYRIDIUM_TEST_RAY_EFFECTS:-true}, RT-Cores ${PYRIDIUM_TEST_HARDWARE_RT:-true}" "$test_dir/native.log"; then
    echo "FAIL: requested reconstruction/effect/backend switches were not applied" >&2; exit 1
fi
if [[ "${PYRIDIUM_TEST_PATH_TRACING:-true}" == false ]] &&
   ! rg -q 'Renderer-Features: .*GI false' "$test_dir/native.log"; then
    echo "FAIL: GI was not disabled" >&2; exit 1
fi
if [[ "${PYRIDIUM_TEST_LIGHTING:-path}" == direct ]] &&
   ! rg -q 'Test-Licht: direct, .*GI false, Szenenreflexionen false, RT-Schatten true' "$test_dir/native.log"; then
    echo "FAIL: direct-lighting comparison preset was not applied" >&2
    exit 1
fi
if ! rg -q 'Aufnahme .*\(1920x1080, Frame' "$test_log"; then
    echo "FAIL: no native Full-HD capture recorded" >&2
    exit 1
fi
if ! rg -q '[1-9][0-9]* Item- und [1-9][0-9]* Custom-Instanzen' "$test_log"; then
    echo "FAIL: item/custom fixtures were not rendered" >&2
    exit 1
fi
if [[ -n "${PYRIDIUM_TEST_PACK:-}" ]] && ! rg -q '[1-9][0-9]* PBR-Normal- und [1-9][0-9]* PBR-Materialkarten' "$test_log"; then
    echo "FAIL: selected PBR resource pack did not supply material maps" >&2
    exit 1
fi
if [[ "${PYRIDIUM_TEST_SPECTATOR:-0}" == 1 ]]; then
    if ! rg -q 'Spectator-DAG: aktiv true, gerendert true' "$test_dir/native.log" ||
       ! rg -q 'Spectator-DAG freigegeben' "$test_dir/native.log" ||
       ! rg -q 'Spectator-Testkamera im geschlossenen' "$test_log" ||
       ! rg -q 'Grafikeinstellungen fensterlos geoeffnet' "$test_log"; then
        echo "FAIL: spectator prebuild/render/settings transitions missing" >&2
        exit 1
    fi
    if [[ "${PYRIDIUM_TEST_SPECTATOR_PREBUILD:-1}" == 1 ]] &&
       ! rg -q 'Spectator-DAG: aktiv false' "$test_dir/native.log"; then
        echo "FAIL: spectator cache was not prebuilt outside spectator mode" >&2
        exit 1
    fi
fi
if rg -q 'Modell-DAG|Geometrie überschreitet|Pyridium: Weltframe fehlgeschlagen|Pyridium: Welt abgeschaltet|Pyridium: Aufnahme fehlgeschlagen|Pyridium: Zeitlimit des Headless-Tests erreicht' "$test_log"; then
    echo "FAIL: renderer geometry or capture error; inspect $test_log" >&2
    exit 1
fi
rg 'Item- und|Chunks sichtbar|PydSurface.*FPS|Aufnahme ' "$test_log" | tail -16
file -- "$test_dir"/shots/*.png
echo "PASS: Full-HD item/custom renderer smoke test. This does not validate GPU worldgen or 165 FPS."
