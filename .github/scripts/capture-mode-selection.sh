#!/usr/bin/env bash
set -euo pipefail

mkdir -p screenshots
for spec in 360x760 390x844; do
  width="${spec%%x*}"
  adb shell wm size "$spec"
  adb shell wm density 160
  adb shell am force-stop com.shinpstudio.chanriva || true
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.example.othello.ModeSelectionScreenshotTest \
    -Pandroid.testInstrumentationRunnerArguments.captureWidth="$width"
  for language in ja en; do
    adb pull "/sdcard/Download/ChanrivaPreviews/mode-${language}-${width}dp.png" screenshots/
  done
done

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.othello.ModeSelectionNavigationAndroidTest
