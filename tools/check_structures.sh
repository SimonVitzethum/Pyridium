#!/usr/bin/env bash
# Regressionstest der GPU-Strukturgenerierung gegen Vanilla (Testwerkzeug StructureHarness, nur Entwicklung).
# Aufruf: tools/check_structures.sh [seed] [radius]
# Schreibt Logs nach build/scratch/check_structures_*.log und gibt je Modus eine PASS/FAIL-Zeile aus.
set -u
seed="${1:-4711}"
radius="${2:-400}"
mkdir -p build/scratch
fail=0
run() {   # name, erwartetes Stichwort, gradle-Argumente...
  local name="$1" key="$2"; shift 2
  local log="build/scratch/check_structures_${name}.log"
  ./gradlew structHarness --offline -q "$@" -Pseed="$seed" > "$log" 2>&1
  if grep -q "$key" "$log"; then echo "PASS  $name"; else echo "FAIL  $name (siehe $log)"; fail=1; fi
}
run place   "PLACEMENT OK" -Pmode=place -Pradius="$radius"
run rings   "RINGS OK"     -Pmode=rings
run gen_overworld "GEN OK" -Pmode=gen -Pradius="$radius" -Pcount=150
run gen_nether    "GEN OK" -Pmode=gen -Pradius="$radius" -Pcount=300 -Pdim=nether
run gen_end       "GEN OK" -Pmode=gen -Pradius="$radius" -Pcount=200 -Pdim=end
run tile    "TILE OK"      -Pmode=tile -Pradius=120
exit $fail
