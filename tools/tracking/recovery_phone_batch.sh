#!/usr/bin/env bash
# Recovery after a loss of position, measured on the phone: the way from 21 November against the way of before
# ("recovery=0", the same build), by replay with the cameras covered. Run with the S21 Ultra charging, glasses unplugged:
#
#   bash tools/tracking/recovery_phone_batch.sh [SERIAL] [OUT_DIR]
#
# About 35 minutes. It refuses to start under 40 % battery unless the phone is charging.
#   1. nothing goes wrong (v_plain against v_plain_old): is anything lost by the new way?
#   2. cameras covered for 6 s, 15 s and 2 s, with the saved map and without, new way and old
#   3. the map library: what makes the worst positions in newly added ground 20 cm (link weight, where adding begins)
# Targets (the owner's): position back within 2 s where feasible, no runaway, under 10 cm when it is back.
set -u
export MSYS_NO_PATHCONV=1
SERIAL="${1:-192.168.1.165:5555}"; OUT="${2:-C:/uvio/vio_bench/recovery}"; REC=walk18.uxrec; LAPTOP_REC=C:/uvio/rec/game.uxrec
HERE="$(cd "$(dirname "$0")/../.." && pwd)"; A="adb -s $SERIAL"; S=/data/local/tmp/slam; F=/sdcard/Android/data/dev.ultraxr/files
level=$($A shell dumpsys battery | grep -m1 level | tr -dc 0-9); charging=$($A shell dumpsys battery | grep -c "powered: true")
if [ "${level:-0}" -lt 40 ] && [ "$charging" -eq 0 ]; then echo "battery $level % and not charging: put the phone on a charger first"; exit 1; fi
mkdir -p "$OUT"; cd "$HERE"
lab() { UXR_IP="${SERIAL%%:*}" python tools/pixel/lab.py replay $REC "$OUT" --runs "$1" 2>&1 | tail -2; }

$A install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
$A shell "cd $F; rm -f room_map.db room_map.db.work.db room_surfaces.txt room_mesh.bin"
echo "== 1 and 2. a map is made; then nothing goes wrong, and the cameras are covered"
lab base,m_new,v_plain,v_blind,v_blind_old,v_plain_old,v_blindb,v_blind_nomap,v_blind_nomap_old,v_blind15,v_blind15_old,v_blind2
python tools/tracking/map_report.py "$OUT" m_new v_plain v_plain_old | cut -c1-330 | tee "$OUT/1-plain.txt"
python tools/tracking/map_cost.py "$OUT" m_new v_plain v_plain_old v_blind v_blind_old v_blindb | tee -a "$OUT/1-plain.txt"
python tools/tracking/vio_loss.py $LAPTOP_REC "$OUT" v_plain_old v_plain | grep -E "^v_|against|lost" | cut -c1-250 | tee -a "$OUT/1-plain.txt"
python tools/tracking/recovery_report.py "$OUT" v_plain v_blind v_blindb v_blind_old v_blind_nomap v_blind_nomap_old v_blind15 v_blind15_old v_blind2 | cut -c1-220 | tee "$OUT/2-recovery.txt"

echo "== 3. the map library: positions in newly added ground"
$A push C:/uvio/rtab/build/uslam/libultra_slam.so $S/ >/dev/null; $A push C:/uvio/rtab/build/uslam/ultra_slam_replay $S/ >/dev/null
$A shell "cd $S && chmod +x ultra_slam_replay; export LD_LIBRARY_PATH=$S:/data/local/tmp/uh
one() { echo \"-- \$1\"; printf \"\$2\" > v.txt; UX_EXTEND='28 60;2 83' ./ultra_slam_replay game.pairs posesG.txt game.pairs posesG.txt rig.txt mapV.db outV.csv 2 30 58 83 3 v.txt | grep -E '^extend [0-9]|^find 58|position'; }
one 'every place of every session (the way of before)' 'Ultra/AddAlways=1\n'
one 'as built: adding after 4 misses and 0.4 m, link weight 400' ''
one 'link weight 40 (a looser link)' 'Ultra/LinkInfo=40\n'
one 'link weight 4000 (a tighter link)' 'Ultra/LinkInfo=4000\n'
one 'adding after 2 misses and 0.15 m (a shorter link)' 'Ultra/AddAfterMisses=2\nUltra/AddAfterM=0.15\n'
one 'adding after 1 miss (the shortest link)' 'Ultra/AddAfterMisses=1\nUltra/AddAfterM=0.0\n'" 2>&1 | cut -c1-230 | tee "$OUT/3-new-ground.txt"
$A shell "cd $F; rm -f room_map.db room_map.db.work.db room_surfaces.txt room_mesh.bin; dumpsys battery | grep level"
echo "done: results in $OUT"
