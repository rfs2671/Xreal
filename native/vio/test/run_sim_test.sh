#!/usr/bin/env bash
# Runs the synthetic stereo+IMU test and stores its output.
# usage: run_sim_test.sh <path/to/uvio_sim_test> <calib dir> <output dir> [extra test args]
# The machine should have ~2 free cores: the test feeds in real time and
# a starved estimator shows up as dropped frames.
set -uo pipefail
BIN="${1:?test binary}"; CALIB_DIR="${2:?calibration dir}"; OUT="${3:?output dir}"; shift 3
mkdir -p "$OUT"
"$BIN" "$CALIB_DIR/basalt_calib_640x480.json" "$CALIB_DIR/basalt_vio_config_640x480.json" --out "$OUT" "$@" \
  > "$OUT/uvio_sim_test.raw.log" 2>&1
rc=$?
# Basalt prints its initial pose matrices / marginalisation prior on stdout; keep
# the raw log and write a filtered one that only has the test's own lines.
grep -Ev '^(T_w_i|vel_w_i|Setting up filter|Finished VIOFilter|marg_H)|^[[:space:]]*[-0-9.e+ ]+$' \
  "$OUT/uvio_sim_test.raw.log" > "$OUT/uvio_sim_test.log"
tail -n 3 "$OUT/uvio_sim_test.log"
echo "exit code: $rc (log: $OUT/uvio_sim_test.log)"
exit $rc
