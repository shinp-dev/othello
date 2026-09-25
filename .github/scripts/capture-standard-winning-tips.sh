#!/usr/bin/env bash
set -euo pipefail

mkdir -p screenshots
for width in 320 360 390; do
  adb shell wm size "${width}x800"
  adb shell wm density 160
  adb shell am force-stop com.shinpstudio.chanriva || true
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.example.othello.StandardWinningTipsScreenshotTest \
    -Pandroid.testInstrumentationRunnerArguments.captureWidth="${width}"
  adb pull \
    "/sdcard/Download/ChanrivaPreviews/standard-winning-tips-${width}dp.png" \
    "screenshots/standard-winning-tips-${width}dp.png"
  test -s "screenshots/standard-winning-tips-${width}dp.png"
  size_bytes=$(stat -c %s "screenshots/standard-winning-tips-${width}dp.png")
  test "${size_bytes}" -gt 20000 || {
    echo "Screenshot at ${width}dp is too small to contain the rendered screen: ${size_bytes} bytes"
    exit 1
  }
  echo "Captured StandardWinningTipsRoute at ${width}dp: screenshots/standard-winning-tips-${width}dp.png"
done

echo "Verified all StandardWinningTipsRoute widths: 320dp, 360dp, 390dp"
