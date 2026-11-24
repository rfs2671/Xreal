cd /data/local/tmp/uh
SYM9="-24,40,70,2;24,40,70,2;-24,58,70,2;24,58,70,2;-24,-22,70,1;24,-22,70,1;-48,40,70,1;48,40,70,1;0,22,70,1"
rm -f v_done.txt
run() { tag=$1; f=$2; shift; shift; ( for kv in "$@"; do export "$kv"; done
  LD_LIBRARY_PATH=g5:. ULTRA_HANDS_VERIFY=0 ./ultra_hands_replay models mercury_calib.json $f.pairs 0 100000 > v_${tag}_$f.csv 2>/dev/null; echo $tag $f >> v_done.txt ) & }
for f in full17 full20 full22; do
  run B $f ULTRA_HANDS_TILE_SCHED=1 "ULTRA_HANDS_TILE_LIST=$SYM9"
  run C $f ULTRA_HANDS_TILE_SCHED=1 "ULTRA_HANDS_TILE_LIST=$SYM9" ULTRA_HANDS_TRUST_TILES=1
  run D $f ULTRA_HANDS_TILE_SCHED=1 ULTRA_HANDS_TRUST_TILES=1
done
wait
