cd /data/local/tmp/uh
for rep in 1 2; do
sh exp.sh A_$rep cad "" 0 1500
sh exp.sh W2_$rep cad "" 0 1500 ULTRA_HANDS_FORCE_WORKERS=2
sh exp.sh W4X1_$rep cad "" 0 1500 ULTRA_HANDS_XNNPACK=1
sh exp.sh W4X1p_$rep cad "taskset f0" 0 1500 ULTRA_HANDS_XNNPACK=1
sh exp.sh W3X1p_$rep cad "taskset f0" 0 1500 ULTRA_HANDS_XNNPACK=1 ULTRA_HANDS_FORCE_WORKERS=3
sh exp.sh W2p_$rep cad "taskset f0" 0 1500 ULTRA_HANDS_FORCE_WORKERS=2
sh exp.sh Dp_$rep cad "taskset f0" 0 1500
done
echo ALLDONE
