#!/usr/bin/env bash
# Fetch + patch the pinned sources for libultra_hands into $WORK (idempotent).
#   Monado 6c9804934324ac5a3e68d64938579dbbeb4d75b3
#     + XR Gate (vladoshub/xr-gate @ 56010adf410fd66f47ea9534e88052304ff0b011)
#       backends/mercury_hand_tracking: monado_overlay/ + patches/monado/*.patch
#     + patches/0001-ultra-hands.patch (this dir)
#   Eigen 3.4.0 (3147391d946bb4b6c68edd901f2add6ac1f31f8c), header only
#   Models: monado/utilities/hand-tracking-models @ 37a8a81bc8f433ac6cbdf2471909d2bac74beca1
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${WORK:-/home/user/build/hands}"
XRGATE_DIR="${XRGATE_DIR:-/home/user/ref/vladoshub_xr-gate}"   # read-only checkout; cloned if missing
XRGATE_COMMIT=56010adf410fd66f47ea9534e88052304ff0b011
MONADO_COMMIT=6c9804934324ac5a3e68d64938579dbbeb4d75b3
EIGEN_COMMIT=3147391d946bb4b6c68edd901f2add6ac1f31f8c
MODELS_COMMIT=37a8a81bc8f433ac6cbdf2471909d2bac74beca1
DET_SHA=1f1a039a266e13dc186bb884430ebd9c8216bdda680ab08a533d4c671f27ed36
KP_SHA=40c0daa598cedb993b54fff17685231b7465d6db342656c401b01c2029efd1d5
mkdir -p "$WORK"

if [[ ! -d "$XRGATE_DIR/.git" ]]; then
  XRGATE_DIR="$WORK/xr-gate"
  [[ -d "$XRGATE_DIR/.git" ]] || GIT_LFS_SKIP_SMUDGE=1 git clone -q https://github.com/vladoshub/xr-gate "$XRGATE_DIR"
  git -C "$XRGATE_DIR" checkout -q "$XRGATE_COMMIT"
fi
B="$XRGATE_DIR/backends/mercury_hand_tracking"

if [[ ! -d "$WORK/monado/.git" ]]; then
  git clone -q https://gitlab.freedesktop.org/monado/monado.git "$WORK/monado"
fi
if [[ ! -f "$WORK/monado/.ultra_hands_patched" ]]; then
  git -C "$WORK/monado" checkout -q -f "$MONADO_COMMIT"
  git -C "$WORK/monado" clean -fdq
  cp -a "$B/monado_overlay/." "$WORK/monado/"
  for p in mercury_xr_upstream_changes mercury_tracking_diagnostics mercury_quality_filter; do
    git -C "$WORK/monado" apply "$B/patches/monado/$p.patch"
  done
  for p in "$HERE"/patches/*.patch; do
    git -C "$WORK/monado" apply "$p"
  done
  touch "$WORK/monado/.ultra_hands_patched"
fi

if [[ ! -d "$WORK/eigen/.git" ]]; then
  git clone -q --depth 1 --branch 3.4.0 https://gitlab.com/libeigen/eigen.git "$WORK/eigen"
fi
[[ "$(git -C "$WORK/eigen" rev-parse HEAD)" == "$EIGEN_COMMIT" ]] || { echo "unexpected Eigen commit" >&2; exit 1; }

# Models (Git LFS). The freedesktop.org LFS object store may be unreachable
# from restricted networks; the gitlab.com mirror of the same repository
# (same commit, same LFS oids) is used as a fallback. Either way the files are
# verified against the sha256 recorded in the upstream LFS pointers.
if [[ "${FETCH_MODELS:-1}" == "1" ]]; then
  M="$WORK/models"
  ok() { [[ -f "$M/$1" ]] && [[ "$(sha256sum "$M/$1" | cut -d' ' -f1)" == "$2" ]]; }
  if ! { ok grayscale_detection_160x160.onnx $DET_SHA && ok grayscale_keypoint_jan18.onnx $KP_SHA; }; then
    for url in https://gitlab.freedesktop.org/monado/utilities/hand-tracking-models.git \
               https://gitlab.com/glab-forks/freedesktop/monado/utilities/hand-tracking-models.git; do
      rm -rf "$M"
      GIT_LFS_SKIP_SMUDGE=1 git clone -q "$url" "$M" || continue
      git -C "$M" checkout -q "$MODELS_COMMIT" || continue
      git -C "$M" lfs pull -I "grayscale_detection_160x160.onnx,grayscale_keypoint_jan18.onnx" || true
      if ok grayscale_detection_160x160.onnx $DET_SHA && ok grayscale_keypoint_jan18.onnx $KP_SHA; then break; fi
    done
  fi
  ok grayscale_detection_160x160.onnx $DET_SHA && ok grayscale_keypoint_jan18.onnx $KP_SHA || { echo "could not fetch models" >&2; exit 1; }
fi
echo "[prepare_sources] ok: $WORK"
