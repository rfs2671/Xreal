#!/usr/bin/env bash
# Build the same sources for host Linux x86_64 and run the C-API smoke test.
# Host deps: OpenCV dev package (apt: libopencv-dev, 4.6.0 on Ubuntu 24.04),
# ONNX Runtime 1.22.0 shared library taken from the PyPI `onnxruntime` wheel,
# ONNX Runtime headers from the Android AAR (same version, same C API).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export WORK="${WORK:-/home/user/build/hands}"
ORT_HEADERS="${ORT_HEADERS:-/home/user/deps/onnxruntime/headers}"
XRGATE_DIR="${XRGATE_DIR:-/home/user/ref/vladoshub_xr-gate}"
CALIB="${CALIB:-$XRGATE_DIR/devices/xreal_ultra/configs/calibration_dataset/final/xreal_air2ultra/ZBBM5DZFMP/unified_480_ccw90/mercury_calib_unified_480_ccw90.json}"
OUT="${OUT:-$HERE/..}"
JOBS="${JOBS:-2}"

bash "$HERE/prepare_sources.sh"

H="$WORK/hostdeps"
if [[ ! -f "$H/ort/libonnxruntime.so.1" ]]; then
  mkdir -p "$H/whl" "$H/ort"
  pip download -q --no-deps --only-binary=:all: --platform manylinux_2_27_x86_64 --platform manylinux_2_28_x86_64 \
      --python-version 312 onnxruntime==1.22.0 -d "$H/whl"
  (cd "$H/ort" && python3 -I -c "import zipfile,glob; z=zipfile.ZipFile(glob.glob('../whl/onnxruntime-1.22.0-*.whl')[0]); [z.extract(n) for n in z.namelist() if 'capi/libonnxruntime' in n or 'LICENSE' in n or 'ThirdParty' in n]")
  ln -sfn onnxruntime/capi/libonnxruntime.so.1.22.0 "$H/ort/libonnxruntime.so"
  ln -sfn onnxruntime/capi/libonnxruntime.so.1.22.0 "$H/ort/libonnxruntime.so.1"
fi

BUILD="$WORK/build-host"
cmake -S "$HERE" -B "$BUILD" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DMONADO_DIR="$WORK/monado" -DEIGEN_DIR="$WORK/eigen" \
  -DORT_INCLUDE_DIR="$ORT_HEADERS" -DORT_LIBRARY="$H/ort/libonnxruntime.so" \
  -DULTRA_HANDS_BUILD_SMOKE_TEST=ON
cmake --build "$BUILD" -j"$JOBS"

if [[ "${RUN_SMOKE:-1}" == "1" ]]; then
  export LD_LIBRARY_PATH="$H/ort:$BUILD:${LD_LIBRARY_PATH:-}"
  # Optional: synthetic stereo frames with a photographed hand (best effort;
  # needs network + python3 with Pillow/numpy). Skipped silently if unavailable.
  ARGS=()
  P="$WORK/handpgm"
  if [[ ! -f "$P/left_hand_20cm_cam0.pgm" ]]; then
    mkdir -p "$WORK/handimg" "$P"
    curl -fsSL -m 60 -o "$WORK/handimg/left_hands.jpg" \
      https://storage.googleapis.com/mediapipe-assets/left_hands.jpg \
      && python3 "$OUT/test/make_hand_stereo.py" "$WORK/handimg/left_hands.jpg" "$P" || true
  fi
  for n in left_hand_20cm right_hand_20cm left_hand_16cm left_hand_30cm_white left_hand_40cm_dark; do
    [[ -f "$P/${n}_cam0.pgm" ]] && ARGS+=(--stereo "$P/${n}_cam0.pgm" "$P/${n}_cam1.pgm" "$n")
  done
  mkdir -p "$OUT/test"
  {
    echo "# host: $(uname -srm), $(nproc) cores, $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs)"
    echo "# host deps: OpenCV $(pkg-config --modversion opencv4 2>/dev/null || echo '?'), ONNX Runtime 1.22.0 (PyPI wheel)"
    echo "# date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    "$BUILD/ultra_hands_smoke" "$WORK/models" "$CALIB" "${ARGS[@]}" ${SMOKE_ARGS:-} 2>&1
    echo "# exit code: $?"
  } | grep -v "Got image with zero standard deviation" | tee "$OUT/test/smoke_output.txt"
  grep -q "^RESULT: PASS" "$OUT/test/smoke_output.txt"
fi
