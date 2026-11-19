#!/usr/bin/env bash
# BOSS 长路径 A/B 对照：提示开/关各 n 轮，图谱固定（learn=false），每轮冷启动重置 App
set -u
PKG=com.hpbr.bosszhipin
RUNNER=com.wangzan101.mika.test/androidx.test.runner.AndroidJUnitRunner
N=${1:-3}

run_one() {
  local name="$1" hints="$2"
  MSYS_NO_PATHCONV=1 adb shell "am force-stop $PKG; input keyevent KEYCODE_HOME" >/dev/null 2>&1
  sleep 3
  echo "=== $name hints=$hints start $(date +%H:%M:%S) ==="
  MSYS_NO_PATHCONV=1 adb shell am instrument -w \
    -e class com.wangzan101.mika.eval.EvalRunTest \
    -e evalRun "$name" \
    -e evalSet boss-two-jobs \
    -e evalAtlasHints "$hints" \
    -e evalAtlasLearn false \
    $RUNNER 2>&1 | tail -4
  sleep 3
}

for i in $(seq 1 "$N"); do run_one "boss11-on-$i" true; done
for i in $(seq 1 "$N"); do run_one "boss11-off-$i" false; done
echo "=== all done $(date +%H:%M:%S) ==="
