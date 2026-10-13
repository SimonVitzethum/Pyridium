#!/usr/bin/env bash
# Terrain-Benchmark: der LOD-Generator laeuft allein (Renderer ruht) mit einem GPU-Anteil (Standard 50 %) und erzeugt Dauerlast wie
# im Flug. Ausgabe: Chunks/s und die GPU-Auslastung wie in nvtop (NVML utilization.gpu). Ziel bei 50 % Anteil: Auslastung >= 47 %
# (94 % des Anteils). Aufruf: tools/bench_terrain.sh [Speicherstand]   Umgebung: BENCH_SECONDS (60), BENCH_DUTY (0.5), BENCH_VIEW (1024)
set -uo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
save=${1:-run/saves/Pyridium Welt}
secs=${BENCH_SECONDS:-60}; duty=${BENCH_DUTY:-0.5}; view=${BENCH_VIEW:-1024}
target=$(python3 -c "print(round($duty*94,1))")
mkdir -p build/scratch
util=build/scratch/bench_terrain_util.log; : > "$util"
( nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits -lms 200 | while read -r u; do echo "$(date +%s.%N) $u"; done ) > "$util" &
sampler=$!
trap 'kill $sampler 2>/dev/null; pkill -P $sampler 2>/dev/null' EXIT
PYRIDIUM_TEST_EXTRA_JVM="-Dpyridium.lodBench=$secs -Dpyridium.lodBenchDuty=$duty -Dpyridium.skipWorldRender=true -Dpyridium.viewChunks=$view ${BENCH_EXTRA_JVM:-}" \
PYRIDIUM_TEST_SECONDS=$((secs + 150)) PYRIDIUM_TEST_TIMEOUT=$((secs + 400)) \
    PYRIDIUM_TEST_DIR_FILE=build/scratch/last_test_dir bash tools/check_renderer.sh "$save" > build/scratch/bench_terrain.log 2>&1
dir=$(cat build/scratch/last_test_dir)
log="$dir/logs/latest.log"
start=$(grep -a "LOD-Bench Start" "$log" | head -1 | sed -E 's/^\[([0-9:]+)\].*/\1/')
res=$(grep -a "LOD-Bench ERGEBNIS" "$log" | tail -1)
if [[ -z "$start" || -z "$res" ]]; then echo "FEHLER: Benchmark lief nicht durch (siehe $log)"; exit 1; fi
end=$(echo "$res" | sed -E 's/^\[([0-9:]+)\].*/\1/')
day=$(date +%F)
t0=$(date -d "$day $start" +%s); t1=$(date -d "$day $end" +%s)
read -r n avg med p10 mn < <(python3 tools/gpu_util_stats.py "$util" $((t0 + 3)) "$t1")
echo "$res" | sed -E 's/^\[[^]]*\] \[[^]]*\] \([^)]*\) //'
echo "GPU-Auslastung (nvtop/NVML) im Fenster: Mittel ${avg} %, Median ${med} %, 10%-Perzentil ${p10} % (${n} Proben)"
echo "Ziel bei GPU-Anteil ${duty}: >= ${target} %"
python3 - "$avg" "$target" <<'PY'
import sys
avg, tgt = float(sys.argv[1]), float(sys.argv[2])
print("PASS" if avg >= tgt else "FAIL", f"(Auslastung {avg} % gegen Ziel {tgt} %)")
PY
