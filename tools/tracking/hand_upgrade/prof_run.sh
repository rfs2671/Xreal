#!/bin/sh
# usage: prof_run.sh LIBDIR FIRST COUNT [ENV=VAL ...]   (on the phone, in /data/local/tmp/uh)
LIB=$1; FIRST=$2; COUNT=$3; shift; shift; shift
for kv in "$@"; do export "$kv"; done
export LD_LIBRARY_PATH=$LIB:. ULTRA_HANDS_PROFILE=1 ULTRA_HANDS_VERIFY=0
./ultra_hands_replay models mercury_calib.json full22.pairs $FIRST $COUNT > /data/local/tmp/uh/prof_out.csv 2> /data/local/tmp/uh/prof_err.txt
grep -c . /data/local/tmp/uh/prof_out.csv
grep ultra_prof /data/local/tmp/uh/prof_err.txt | tail -8
