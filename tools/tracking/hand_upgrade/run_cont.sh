cd /data/local/tmp/uh
SYM9="-24,40,70,2;24,40,70,2;-24,58,70,2;24,58,70,2;-24,-22,70,1;24,-22,70,1;-48,40,70,1;48,40,70,1;0,22,70,1"
run() { tag=$1; lib=$2; f=$3; shift; shift; shift
  ( for kv in "$@"; do export "$kv"; done
    LD_LIBRARY_PATH=$lib:. ULTRA_HANDS_VERIFY=0 ./ultra_hands_replay models mercury_calib.json $f 0 100000 > c_${tag}_$f.csv 2> c_${tag}_$f.err; echo done $tag $f >> c_done.txt ) &
}
rm -f c_done.txt
for f in full17.pairs full20.pairs full22.pairs; do
  run base t2 $f
  run new t3 $f ULTRA_HANDS_TILE_SCHED=1 "ULTRA_HANDS_TILE_LIST=$SYM9" ULTRA_HANDS_RECALL=1
done
wait
