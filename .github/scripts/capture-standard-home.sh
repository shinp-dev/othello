#!/usr/bin/env bash
set -euo pipefail

mkdir -p screenshots
for width in 320 360 390; do
  adb shell wm size "${width}x800"
  adb shell wm density 160
  adb shell am force-stop com.shinpstudio.chanriva || true
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.example.othello.StandardHomeScreenshotTest \
    -Pandroid.testInstrumentationRunnerArguments.captureWidth="$width"
  adb pull \
    "/sdcard/Download/ChanrivaPreviews/standard-home-${width}dp.png" \
    "screenshots/standard-home-${width}dp.png"
  test -s "screenshots/standard-home-${width}dp.png"
  echo "Captured StandardHomeScreen screenshot at ${width}dp: screenshots/standard-home-${width}dp.png"
done

echo "Verified all StandardHomeScreen screenshot widths: 320dp, 360dp, 390dp"
