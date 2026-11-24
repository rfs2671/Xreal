cd /data/local/tmp/uh
SYM9="-24,40,70,2;24,40,70,2;-24,58,70,2;24,58,70,2;-24,-22,70,1;24,-22,70,1;-48,40,70,1;48,40,70,1;0,22,70,1"
rm -f d_done.txt
run() { tag=$1; shift; ( for kv in "$@"; do export "$kv"; done
  LD_LIBRARY_PATH=t3:. ULTRA_HANDS_VERIFY=0 ./ultra_hands_replay models mercury_calib.json full17.pairs 0 100000 > d_$tag.csv 2>/dev/null; echo $tag >> d_done.txt ) & }
run ss1 ULTRA_HANDS_TILE_SCHED=1 "ULTRA_HANDS_TILE_LIST=$SYM9"
run ss2 ULTRA_HANDS_TILE_SCHED=1 "ULTRA_HANDS_TILE_LIST=$SYM9"
run b1
run b2
wait
