#!/bin/sh
# usage: exp.sh TAG LIBDIR "PREFIX CMD" FIRST COUNT [ENV=VAL ...]   (phone, /data/local/tmp/uh)
TAG=$1; LIB=$2; PRE=$3; FIRST=$4; COUNT=$5; shift; shift; shift; shift; shift
for kv in "$@"; do export "$kv"; done
export LD_LIBRARY_PATH=$LIB:. ULTRA_HANDS_PROFILE=1 ULTRA_HANDS_VERIFY=${ULTRA_HANDS_VERIFY:-0}
$PRE ./ultra_hands_replay ${MODELS:-models} mercury_calib.json ${PAIRS:-full22.pairs} $FIRST $COUNT > exp_$TAG.csv 2> exp_$TAG.err
awk -F, -v tag=$TAG 'NR>1 && $3+0>0 { if ($2+0>0) {h[++nh]=$3+0} else {n[++nn]=$3+0} } END { cmd="sort -n"; for(i=1;i<=nh;i++) print h[i] | "sort -n > /data/local/tmp/uh/_h.txt"; close("sort -n > /data/local/tmp/uh/_h.txt"); for(i=1;i<=nn;i++) print n[i] | "sort -n > /data/local/tmp/uh/_n.txt"; close("sort -n > /data/local/tmp/uh/_n.txt"); }' exp_$TAG.csv
for k in h n; do c=$(wc -l < _$k.txt); [ $c -gt 0 ] && echo "$TAG $k n=$c median=$(sed -n "$((c/2+1))p" _$k.txt) p95=$(sed -n "$((c*95/100+1))p" _$k.txt) p99=$(sed -n "$((c*99/100+1))p" _$k.txt)"; done
