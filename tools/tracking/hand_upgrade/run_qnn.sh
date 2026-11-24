#!/bin/bash
# Runs the hand models on the phone's Hexagon through ONNX Runtime's QNN provider, once the Qualcomm libraries are in C:\uvio\qnn\sdk\
#   (libQnnHtp.so, libQnnHtpV68Stub.so, libQnnSystem.so, libQnnHtpPrepare.so from the SDK's lib/aarch64-android/ and
#    libQnnHtpV68Skel.so from the SDK's lib/hexagon-v68/unsigned/).
# usage: bash run_qnn.sh [phone address]     (default 192.168.1.165:5555)
export MSYS_NO_PATHCONV=1
S=${1:-192.168.1.165:5555}; A="adb -s $S"; D=/data/local/tmp/qnn
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="$HERE/../sdk"
for f in libQnnHtp.so libQnnHtpV68Stub.so libQnnSystem.so libQnnHtpPrepare.so libQnnHtpV68Skel.so; do [ -f "$SDK/$f" ] || { echo "missing $SDK/$f"; exit 1; }; done
timeout 20 adb connect $S >/dev/null
$A shell "mkdir -p $D/models $D/q_a8 $D/q_a16"
for f in qnn_bench libonnxruntime.so libc++_shared.so; do $A push "$HERE/$f" $D/ >/dev/null; done
for f in libQnnHtp.so libQnnHtpV68Stub.so libQnnSystem.so libQnnHtpPrepare.so libQnnHtpV68Skel.so; do $A push "$SDK/$f" $D/ >/dev/null; done
for d in models q_a8 q_a16; do $A push "$HERE/$d/." $D/$d/ >/dev/null; done
$A shell "chmod +x $D/qnn_bench"
run() { # model ep fp16
  $A shell "logcat -c; cd $D; export LD_LIBRARY_PATH=$D ADSP_LIBRARY_PATH=$D; QNN_LOG=1 ./qnn_bench $1 $2 100 $3 burst 2>&1 | tail -2; echo '  provider log:'; logcat -d | grep -i -E 'SetupBackend failed|Unable to load backend|Number of nodes|nodes in the graph|QNN EP|supported by QNN|not supported by QNN' | head -4 | cut -c60-260"
}
for m in grayscale_detection_160x160.onnx grayscale_keypoint_jan18.onnx; do
  echo "=== $m, CPU (float)";                      run models/$m cpu 0
  echo "=== $m, HTP, float model, fp16 compute";   run models/$m qnn 1
  echo "=== $m, HTP, 8-bit activations";           run q_a8/$m qnn 0
  echo "=== $m, HTP, 16-bit activations";          run q_a16/$m qnn 0
done
