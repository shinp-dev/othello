#!/usr/bin/env bash
set -euo pipefail

mkdir -p screenshots
for width in 320 360 390; do
  adb shell wm size "${width}x800"
  adb shell wm density 160
  adb shell am force-stop com.shinpstudio.chanriva || true
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.example.othello.StandardAiAnimalSelectionScreenshotTest \
    -Pandroid.testInstrumentationRunnerArguments.captureWidth="$width"
  adb pull \
    "/sdcard/Download/ChanrivaPreviews/standard-animal-selection-${width}dp.png" \
    "screenshots/standard-animal-selection-${width}dp.png"
  test -s "screenshots/standard-animal-selection-${width}dp.png"
  echo "Captured StandardAiPlayerSelectionContent screenshot at ${width}dp: screenshots/standard-animal-selection-${width}dp.png"
done

echo "Verified all animal selection screenshot widths: 320dp, 360dp, 390dp"
