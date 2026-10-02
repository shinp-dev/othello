#!/usr/bin/env bash
set -euo pipefail

mkdir -p screenshots
for width in 320 360 390; do
  adb shell wm size "${width}x800"
  adb shell wm density 160
  adb shell am force-stop com.shinpstudio.chanriva || true
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.example.othello.DeepEnjoyHomeScreenshotTest \
    -Pandroid.testInstrumentationRunnerArguments.captureWidth="$width"
  adb pull \
    "/sdcard/Download/ChanrivaPreviews/deep-enjoy-home-${width}dp.png" \
    "screenshots/deep-enjoy-home-${width}dp.png"
  test -s "screenshots/deep-enjoy-home-${width}dp.png"
done
