#!/usr/bin/env bash
set -euo pipefail

mkdir -p screenshots
for width in 320 360 390; do
  adb shell wm size "${width}x800"
  adb shell wm density 160
  adb shell am force-stop com.shinpstudio.chanriva || true
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.example.othello.PeopleSocialScreenScreenshotTest \
    -Pandroid.testInstrumentationRunnerArguments.captureWidth="$width"
  adb pull \
    "/sdcard/Download/ChanrivaPreviews/people-social-${width}dp.png" \
    "screenshots/people-social-${width}dp.png"
  test -s "screenshots/people-social-${width}dp.png"
  echo "Captured PeopleSocialScreen at ${width}dp"
done
