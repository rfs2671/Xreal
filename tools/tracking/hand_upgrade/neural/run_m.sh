cd /data/local/tmp/uh
rm -f m_done.txt
export LD_LIBRARY_PATH=g6:. ULTRA_HANDS_VERIFY=0
for f in full17 full20 full22 full23; do
  for m in models models_ft1 models_ft2; do
    ( ./ultra_hands_replay $m mercury_calib.json $f.pairs 0 100000 > m_${m}_$f.csv 2>/dev/null; echo $m $f >> m_done.txt ) &
  done
  wait
done
