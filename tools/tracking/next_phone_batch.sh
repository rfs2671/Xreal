#!/usr/bin/env bash
# Everything of 20 November (night) that still needs the phone, in one go. Built and unit-tested, NOT yet measured:
# the phone was at 20 % and off the charger. Run with the S21 Ultra charging, glasses unplugged:
#
#   bash tools/tracking/next_phone_batch.sh [SERIAL] [OUT_DIR]
#
# It refuses to start under 50 % battery unless the phone is charging. About 70 minutes.
#   1. the map library by itself: places are added only where the map has none (three sessions over the same room
#      must add next to nothing; a session into new ground must add that ground and stay tied)
#   2. the estimator's time for a frame under each setting of tools/pixel/lab.py "e_*" (target: under 25 ms, same path)
#   3. the app: a map made, three sessions over it (a_grow*: the file must stop growing), against the old way (a_always*)
#   4. corrections: slips of 8 cm, 20 cm and 1 m (the last must now glide, not jump)
#   5. reliability: cameras covered for 6 and 15 s with and without the map; sessions at 0.35, 0.6 and 1.6 of the light
set -u
export MSYS_NO_PATHCONV=1
SERIAL="${1:-192.168.1.165:5555}"; OUT="${2:-C:/uvio/vio_bench/night}"; REC=walk18.uxrec; LAPTOP_REC=C:/uvio/rec/game.uxrec
HERE="$(cd "$(dirname "$0")/../.." && pwd)"; A="adb -s $SERIAL"; S=/data/local/tmp/slam; F=/sdcard/Android/data/dev.ultraxr/files
level=$($A shell dumpsys battery | grep -m1 level | tr -dc 0-9); charging=$($A shell dumpsys battery | grep -c "powered: true")
if [ "${level:-0}" -lt 50 ] && [ "$charging" -eq 0 ]; then echo "battery $level % and not charging: put the phone on a charger first"; exit 1; fi
mkdir -p "$OUT"; cd "$HERE"
lab() { UXR_IP="${SERIAL%%:*}" python tools/pixel/lab.py replay $REC "$OUT" --runs "$1" 2>&1 | tail -2; }

echo "== 1. the map library: adding only where the map has no places"
$A push C:/uvio/rtab/build/uslam/libultra_slam.so $S/ >/dev/null; $A push C:/uvio/rtab/build/uslam/ultra_slam_replay $S/ >/dev/null
$A shell "cd $S && chmod +x ultra_slam_replay; export LD_LIBRARY_PATH=$S:/data/local/tmp/uh
echo '-- map 2-30; three sessions over 2-30 again; find 58-83 (the map should stay near 58 places)'
echo 'Ultra/AddAlways=0' > adaptive.txt
UX_EXTEND='2 30;2 30;2 30' ./ultra_slam_replay game.pairs posesG.txt game.pairs posesG.txt rig.txt mapA.db outA.csv 2 30 58 83 3 adaptive.txt | grep -v ultra_slam; ls -la mapA.db
echo '-- map 2-30; sessions 28-60, 2-60, 2-83 (new ground once each); find 58-83'
UX_EXTEND='28 60;2 60;2 83' ./ultra_slam_replay game.pairs posesG.txt game.pairs posesG.txt rig.txt mapB.db outB.csv 2 30 58 83 3 adaptive.txt | grep -v ultra_slam; ls -la mapB.db
echo '-- the old way (every place of every session) for comparison'
echo 'Ultra/AddAlways=1' > always.txt
UX_EXTEND='28 60;2 60;2 83' ./ultra_slam_replay game.pairs posesG.txt game.pairs posesG.txt rig.txt mapC.db outC.csv 2 30 58 83 3 always.txt | grep -v ultra_slam; ls -la mapC.db
logcat -d | grep 'ultra_slam: \(nothing here\|back among\)' | tail -12" 2>&1 | cut -c1-230 | tee "$OUT/1-library.txt"

$A install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
echo "== 2. the estimator's time for a frame"
lab base,e_base,e_t3,e_t4,e_nice,e_nice4t,e_baseb,e_it5,e_it4,e_grid60,e_grid70,e_of3,e_kf5,e_basec,e_mix,e_mixb,e_mix2
python tools/tracking/map_cost.py "$OUT" e_base e_t3 e_t4 e_nice e_nice4t e_baseb e_it5 e_it4 e_grid60 e_grid70 e_of3 e_kf5 e_basec e_mix e_mixb e_mix2 | tee "$OUT/2-estimator.txt"
python tools/tracking/vio_loss.py $LAPTOP_REC "$OUT" e_base e_baseb e_t3 e_t4 e_nice e_nice4t e_it5 e_it4 e_grid60 e_grid70 e_of3 e_kf5 e_mix e_mixb e_mix2 | grep -E "^e_|against" | cut -c1-260 | tee -a "$OUT/2-estimator.txt"

echo "== 3. the app: a map that is added to only where it has no places"
$A shell "cd $F; rm -f room_map.db room_map.db.work.db room_surfaces.txt room_mesh.bin"
lab base,m_new,a_grow,a_growb,a_growc,m_find
$A shell "ls -la $F | grep room_" | tee "$OUT/3-grow.txt"
python tools/tracking/map_report.py "$OUT" m_new a_grow a_growb a_growc m_find | cut -c1-330 | tee -a "$OUT/3-grow.txt"
python tools/tracking/map_cost.py "$OUT" m_new a_grow a_growb a_growc m_find | tee -a "$OUT/3-grow.txt"
for t in a_grow a_growc; do grep -E "room memory|furniture" "$OUT/$t-notes.txt" | tail -4 | cut -c1-220; done | tee -a "$OUT/3-grow.txt"

echo "== 4. corrections against the map; 5. reliability"
lab base,s_8,s_20,s_100,s_100g,g_mesh,g_meshb,x_blind,x_blind_nomap,x_blind15,x_dim,x_dark,x_bright
python tools/tracking/map_slip_report.py "$OUT" m_find s_8 s_20 s_100 s_100g | cut -c1-330 | tee "$OUT/4-slips.txt"
python tools/tracking/map_cost.py "$OUT" m_find g_mesh g_meshb s_8 | tee -a "$OUT/4-slips.txt"
for t in g_mesh g_meshb; do grep -E "room memory|furniture|room mesh" "$OUT/$t-notes.txt" | tail -4 | cut -c1-220; done | tee -a "$OUT/4-slips.txt"
python tools/tracking/map_report.py "$OUT" m_new x_blind x_blind15 x_dim x_dark x_bright | cut -c1-330 | tee "$OUT/5-reliability.txt"
python tools/tracking/vio_loss.py $LAPTOP_REC "$OUT" m_find x_blind x_blind_nomap x_blind15 x_dim x_dark x_bright | cut -c1-260 | tee -a "$OUT/5-reliability.txt"
for t in x_blind x_blind15; do grep -E "glid|put back|is where the map" "$OUT/$t-notes.txt" | cut -c1-200; done | tee -a "$OUT/5-reliability.txt"
$A shell "cd $F; rm -f room_map.db room_map.db.work.db room_surfaces.txt room_mesh.bin; dumpsys battery | grep level"
echo "done: results in $OUT"
