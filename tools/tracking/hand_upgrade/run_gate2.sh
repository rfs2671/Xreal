cd /data/local/tmp/uh
rm -f gate_done.txt
export LD_LIBRARY_PATH=g9:. ULTRA_HANDS_VERIFY=0
for f in full23 full22 full17 full20; do
  for m in models models_ft2; do
    for t in 0.45 0.50; do
      ( ULTRA_HANDS_JC_GATE=$t nice -n 19 ./ultra_hands_replay $m mercury_calib.json $f.pairs 0 100000 > gate_${m}_${t}_$f.csv 2>/dev/null; echo $f $m $t >> gate_done.txt ) &
    done
  done
  wait
done
