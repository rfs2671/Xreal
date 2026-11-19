#!/system/bin/sh
# Starts Ultra XR's shell helper, which lets other apps run in its windows. It has to be started again after the
# phone restarts or Ultra XR is updated:
#   adb push tools/pixel/uxr_helper.sh /data/local/tmp/ && adb shell sh /data/local/tmp/uxr_helper.sh
for p in $(pgrep -f 'app_process / dev.ultraxr.shell.Shell'); do kill $p 2>/dev/null; done
P=$(pm path dev.ultraxr | head -1 | cut -d: -f2)
[ -z "$P" ] && { echo "Ultra XR is not installed"; exit 1; }
CLASSPATH=$P nohup app_process / dev.ultraxr.shell.ShellMain > /data/local/tmp/uxr-shell.log 2>&1 &
sleep 2; cat /data/local/tmp/uxr-shell.log
