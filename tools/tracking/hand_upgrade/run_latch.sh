cd /data/local/tmp/uh
rm -f latch_done.txt
export LD_LIBRARY_PATH=g10:. ULTRA_HANDS_VERIFY=0
for f in full23 full22 full17 full20; do
  for m in models models_ft2; do
    for c in "0.5 4" "0.55 6"; do
      set -- $c
      ( ULTRA_HANDS_JC_GATE=$1 ULTRA_HANDS_JC_LATCH=$2 nice -n 19 ./ultra_hands_replay $m mercury_calib.json $f.pairs 0 100000 > latch_${m}_$1_$2_$f.csv 2>/dev/null; echo $f $m $1 $2 >> latch_done.txt ) &
    done
  done
  wait
done
