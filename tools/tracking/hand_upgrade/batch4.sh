cd /data/local/tmp/uh
for rep in 1 2; do
sh exp.sh f32_$rep prof "" 0 1500
MODELS=fp16 sh exp.sh h16_$rep prof "" 0 1500 ULTRA_HANDS_XNNPACK=0
MODELS=fp16 sh exp.sh h16big_$rep prof "taskset f0" 0 1500 ULTRA_HANDS_XNNPACK=0
done
echo ALLDONE
