#!/usr/bin/env python3
"""Auswertung der GPU-Auslastung (NVML utilization.gpu, wie in nvtop) aus der Samplerdatei 'epoch util' im Zeitfenster.
Aufruf: gpu_util_stats.py <datei> <von_epoch> <bis_epoch>  -> 'n avg median p10 min'"""
import sys, statistics
f, a, b = sys.argv[1], float(sys.argv[2]), float(sys.argv[3])
v = []
for line in open(f):
    p = line.split()
    if len(p) == 2:
        try:
            t, u = float(p[0]), float(p[1])
        except ValueError:
            continue
        if a <= t <= b:
            v.append(u)
if not v:
    print("0 0 0 0 0")
else:
    v.sort()
    print(len(v), round(sum(v) / len(v), 1), statistics.median(v), v[len(v) // 10], v[0])
