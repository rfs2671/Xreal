cd /data/local/tmp/uh
for P in full17 full20; do
export PAIRS=$P.pairs
sh exp.sh ${P}_A cad "" 0 1500
sh exp.sh ${P}_B3 cad "" 0 1500 ULTRA_HANDS_DETECT_EVERY=3
sh exp.sh ${P}_B2 cad "" 0 1500 ULTRA_HANDS_DETECT_EVERY=2
sh exp.sh ${P}_D cad "taskset f0" 0 1500
done
echo ALLDONE
