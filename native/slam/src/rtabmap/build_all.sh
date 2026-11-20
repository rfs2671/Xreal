#!/usr/bin/env bash
# Whole pipeline: sources -> librtabmap_core.so and libultra_slam.so in the app  (JOBS=4 by default)
set -euo pipefail
D="$(cd "$(dirname "$0")" && pwd)"
for s in 00_fetch 01_small_deps 02_boost 03_pcl 04_g2o 05_rtabmap 06_combine 07_ultra_slam; do echo "== $s"; bash "$D/$s.sh"; done
