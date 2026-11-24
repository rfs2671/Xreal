cd /data/local/tmp/uh
for rep in 1 2; do
sh exp.sh base$rep prof "" 0 1500
sh exp.sh big$rep prof "taskset f0" 0 1500
sh exp.sh xn1_$rep prof "" 0 1500 ULTRA_HANDS_XNNPACK=1
sh exp.sh xn2_$rep prof "" 0 1500 ULTRA_HANDS_XNNPACK=2
sh exp.sh xn0_$rep prof "" 0 1500 ULTRA_HANDS_XNNPACK=0
done
echo ALLDONE
