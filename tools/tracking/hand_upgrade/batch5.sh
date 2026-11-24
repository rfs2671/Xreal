cd /data/local/tmp/uh
for rep in 1 2; do
sh exp.sh A_$rep cad "" 0 1500
sh exp.sh B_$rep cad "" 0 1500 ULTRA_HANDS_DETECT_EVERY=3
sh exp.sh C_$rep cad "taskset f0" 0 1500 ULTRA_HANDS_DETECT_EVERY=3
sh exp.sh D_$rep cad "taskset f0" 0 1500
sh exp.sh E_$rep cad "" 0 1500 ULTRA_HANDS_FORCE_WORKERS=4
sh exp.sh F_$rep cad "" 0 1500 ULTRA_HANDS_FORCE_WORKERS=3
done
echo ALLDONE
