#!/bin/sh
# The picture one eye of the glasses would get from the desktop, without the glasses on (debug builds):
#   eye.sh SERIAL OUT.png
# The desktop must be open on the phone (its preview showing). See DesktopRenderer.eyeCapture.
export MSYS_NO_PATHCONV=1
S=$1; OUT=$2
adb -s "$S" shell "run-as dev.ultraxr sh -c 'rm -f files/eye_capture.png; touch files/eye_capture.req'"
for i in 1 2 3 4 5 6 7 8 9 10; do
  sleep 0.4
  if adb -s "$S" shell "run-as dev.ultraxr ls files/eye_capture.png" >/dev/null 2>&1; then
    adb -s "$S" exec-out run-as dev.ultraxr cat files/eye_capture.png > "$OUT"; echo "wrote $OUT"; exit 0
  fi
done
echo "no picture: is the desktop open with its preview showing?"; exit 1
