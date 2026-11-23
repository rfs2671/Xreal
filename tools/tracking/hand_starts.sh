# usage: sh starts.sh TAG LIBDIR [ENV=VALUE ...]   (on the phone, in /data/local/tmp/uh)
# The tracker is started cold at 48 moments at which a hand is plainly in both pictures and stays there, and run for
# four seconds with every pair processed: how long until a hand is first reported?
TAG=$1; LIB=$2; shift; shift
for kv in "$@"; do export "$kv"; done
export LD_LIBRARY_PATH=$LIB:.
mkdir -p st_$TAG; rm -f st_$TAG/*
for s in 5.0 5.5 6.0 6.5 7.0 7.5 8.0 8.5 9.0 9.5 10.0 10.5 11.0 11.5 12.0 12.5 17.0 17.5 18.0 18.5 19.0 19.5 20.0 20.5 21.0 21.5 22.0 22.5 31.0 31.5 32.0 32.5 33.0 33.5 34.0 34.5 46.0 46.5 47.0 47.5 48.0 48.5 49.0 49.5 84.0 84.5 90.0 90.5; do
  first=$(echo "$s" | awk '{printf "%d", $1*30}')
  ./ultra_hands_replay models mercury_calib.json full22.pairs $first 120 > st_$TAG/$s.csv 2>/dev/null
done
echo done $TAG
