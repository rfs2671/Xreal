#!/usr/bin/env bash
# Fetch basalt + dependencies at pinned revisions into <workdir> and apply the
# basalt patch. Usage: fetch_deps.sh <workdir> [--with-opencv]
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${1:?usage: fetch_deps.sh <workdir> [--with-opencv]}"
WITH_OPENCV="${2:-}"
# shellcheck disable=SC1091
source "$HERE/versions.env"
export GIT_LFS_SKIP_SMUDGE=1
mkdir -p "$WORK/deps"

fetch() {  # fetch <dir> <url> <rev>
  local dir="$1" url="$2" rev="$3"
  if [ -d "$dir/.git" ] && [ "$(git -C "$dir" rev-parse HEAD)" = "$rev" ]; then
    echo "[fetch] $dir already at $rev"
    return
  fi
  rm -rf "$dir"
  git init -q "$dir"
  git -C "$dir" remote add origin "$url"
  git -C "$dir" fetch -q --depth 1 origin "$rev"
  git -C "$dir" -c advice.detachedHead=false checkout -q FETCH_HEAD
  [ "$(git -C "$dir" rev-parse HEAD)" = "$rev" ] || { echo "revision mismatch for $dir" >&2; exit 1; }
  echo "[fetch] $dir @ $rev"
}

fetch "$WORK/basalt"              "$BASALT_URL"         "$BASALT_REV"
fetch "$WORK/deps/basalt-headers" "$BASALT_HEADERS_URL" "$BASALT_HEADERS_REV"
fetch "$WORK/deps/eigen"          "$EIGEN_URL"          "$EIGEN_REV"
fetch "$WORK/deps/sophus"         "$SOPHUS_URL"         "$SOPHUS_REV"
fetch "$WORK/deps/cereal"         "$CEREAL_URL"         "$CEREAL_REV"
fetch "$WORK/deps/fmt"            "$FMT_URL"            "$FMT_REV"
fetch "$WORK/deps/oneTBB"         "$ONETBB_URL"         "$ONETBB_REV"
fetch "$WORK/deps/magic_enum"     "$MAGIC_ENUM_URL"     "$MAGIC_ENUM_REV"
fetch "$WORK/deps/json"           "$JSON_URL"           "$JSON_REV"
if [ "$WITH_OPENCV" = "--with-opencv" ]; then
  fetch "$WORK/deps/opencv"       "$OPENCV_URL"         "$OPENCV_REV"
fi

# Apply the basalt patch(es) once.
if ! grep -q "load_from_string" "$WORK/basalt/include/basalt/utils/vio_config.h"; then
  for p in "$HERE"/patches/*.patch; do
    git -C "$WORK/basalt" apply --check "$p"
    git -C "$WORK/basalt" apply "$p"
    echo "[fetch] applied $(basename "$p")"
  done
fi
