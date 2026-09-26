#!/usr/bin/env bash
set -euo pipefail

mkdir -p screenshots
for width in 320 360 390; do
  adb shell wm size "${width}x800"
  adb shell wm density 160
  adb shell am force-stop com.shinpstudio.chanriva || true
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.example.othello.ChanrivaNameSelectionScreenshotTest \
    -Pandroid.testInstrumentationRunnerArguments.captureWidth="${width}"
  adb pull \
    "/sdcard/Download/ChanrivaPreviews/chanriva-name-selection-${width}dp.png" \
    "screenshots/chanriva-name-selection-${width}dp.png"
  test -s "screenshots/chanriva-name-selection-${width}dp.png"
  echo "Captured ChanrivaNameSelectionScreen at ${width}dp: screenshots/chanriva-name-selection-${width}dp.png"
done

echo "Verified all ChanrivaNameSelectionScreen widths: 320dp, 360dp, 390dp"
