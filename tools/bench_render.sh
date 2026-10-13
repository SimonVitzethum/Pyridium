#!/usr/bin/env bash
# Render-Benchmark: Full-HD, native Aufloesung, Pfad-Tracing, 1024 Chunks Sichtweite; misst GPU-Zeit je Frame (Pyrit-Zeitbericht) und die
# GPU-Auslastung wie in nvtop, gemittelt ab dem Moment, in dem die Welt vollstaendig geladen ist (0 offen). Ziele: <= 6 ms und >= 94 %.
# Aufruf: tools/bench_render.sh [Speicherstand]   Umgebung: BENCH_SECONDS (170), BENCH_VIEW (1024), BENCH_PACK (Shaderpack)
set -uo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
save=${1:-run/saves/Pyridium Welt}
secs=${BENCH_SECONDS:-170}; view=${BENCH_VIEW:-1024}
mkdir -p build/scratch
util=build/scratch/bench_render_util.log; : > "$util"
( nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits -lms 200 | while read -r u; do echo "$(date +%s.%N) $u"; done ) > "$util" &
sampler=$!
trap 'kill $sampler 2>/dev/null; pkill -P $sampler 2>/dev/null' EXIT
extra="-Dpyridium.viewChunks=$view ${BENCH_EXTRA_JVM:-}"
[[ -n "${BENCH_PACK:-}" ]] && extra="$extra -Dpyridium.shaderpack=$BENCH_PACK"
PYRIT_GPU_TIMING=1 PYRIT_GPU_TIMING_PASSES=1 PYRIDIUM_TEST_EXTRA_JVM="$extra" PYRIDIUM_TEST_MODE=${PYRIDIUM_TEST_MODE:-native} \
PYRIDIUM_TEST_SECONDS=$secs PYRIDIUM_TEST_TIMEOUT=$((secs + 200)) \
    PYRIDIUM_TEST_DIR_FILE=build/scratch/last_test_dir bash tools/check_renderer.sh "$save" > build/scratch/bench_render.log 2>&1
dir=$(cat build/scratch/last_test_dir)
log="$dir/logs/latest.log"
day=$(date +%F)
last=$(grep -a "(PydSurface).*FPS" "$log" | tail -1 | sed -E 's/^\[([0-9:]+)\].*/\1/')
[[ -z "$last" ]] && { echo "FEHLER: kein Renderlauf (siehe $log)"; exit 1; }
t1=$(date -d "$day $last" +%s)
full=$(grep -a "(PyritWorld).*, 0 offen" "$log" | head -1 | sed -E 's/^\[([0-9:]+)\].*/\1/')
note=""
if [[ -n "$full" ]]; then t0=$(date -d "$day $full" +%s); else t0=$((t1 - 40)); note=" (Welt nicht vollstaendig geladen: letzte 40 s)"; fi
t0=$((t0 + 5))
read -r n avg med p10 mn < <(python3 tools/gpu_util_stats.py "$util" "$t0" "$t1")
# GPU-Zeit je Frame: Zeitberichte 'gesamt X ms' im Fenster
python3 - "$log" "$day" "$t0" "$t1" <<'PY'
import re, sys, statistics, datetime
log, day, t0, t1 = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
vals, fps = [], []
for line in open(log, errors="ignore"):
    m = re.match(r"\[(\d\d:\d\d:\d\d)\]", line)
    if not m:
        continue
    t = int(datetime.datetime.strptime(day + " " + m.group(1), "%Y-%m-%d %H:%M:%S").timestamp())
    if not (t0 <= t <= t1):
        continue
    g = re.search(r"gesamt ([0-9.,]+) ms", line)
    if g and "GPU-Zeit" in line:
        vals.append(float(g.group(1).replace(",", ".")))
    f = re.search(r"Pyridium: ([0-9.,]+) FPS", line)
    if f:
        fps.append(float(f.group(1).replace(",", ".")))
if vals:
    vals.sort()
    print(f"GPU-Zeit je Frame: min {vals[0]:.2f} ms, Median {statistics.median(vals):.2f} ms ({len(vals)} Berichte)")
else:
    print("GPU-Zeit je Frame: keine Berichte im Fenster")
if fps:
    print(f"FPS (Fenster a 5 s): Median {statistics.median(fps):.1f}, min {min(fps):.1f}, max {max(fps):.1f}")
open("build/scratch/bench_render_min.txt", "w").write(str(vals[0] if vals else 999))
PY
echo "GPU-Auslastung (nvtop/NVML) im Fenster${note}: Mittel ${avg} %, Median ${med} %, 10%-Perzentil ${p10} % (${n} Proben)"
min=$(cat build/scratch/bench_render_min.txt)
python3 - "$avg" "$min" <<'PY'
import sys
avg, ms = float(sys.argv[1]), float(sys.argv[2])
ok_u, ok_t = avg >= 94.0, ms <= 6.0
print("Ziel Auslastung >= 94 %:", "PASS" if ok_u else "FAIL", f"({avg} %)")
print("Ziel GPU-Zeit <= 6 ms:", "PASS" if ok_t else "FAIL", f"(bester Bericht {ms} ms)")
PY
