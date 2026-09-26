#!/usr/bin/env bash
set -euo pipefail

mkdir -p screenshots
for width in 320 360 390; do
  adb shell wm size "${width}x800"
  adb shell wm density 160
  adb shell am force-stop com.shinpstudio.chanriva || true
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.example.othello.PeoplePlayLobbyScreenshotTest \
    -Pandroid.testInstrumentationRunnerArguments.captureWidth="$width" \
    -Pandroid.testInstrumentationRunnerArguments.captureLanguage=ja
  adb pull \
    "/sdcard/Download/ChanrivaPreviews/people-play-lobby-ja-${width}dp.png" \
    "screenshots/people-play-lobby-ja-${width}dp.png"
  test -s "screenshots/people-play-lobby-ja-${width}dp.png"
done

# Capture English at a narrow supported width as a localization overflow check.
adb shell wm size 320x800
adb shell wm density 160
adb shell am force-stop com.shinpstudio.chanriva || true
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.othello.PeoplePlayLobbyScreenshotTest \
  -Pandroid.testInstrumentationRunnerArguments.captureWidth=320 \
  -Pandroid.testInstrumentationRunnerArguments.captureLanguage=en
adb pull \
  /sdcard/Download/ChanrivaPreviews/people-play-lobby-en-320dp.png \
  screenshots/people-play-lobby-en-320dp.png
test -s screenshots/people-play-lobby-en-320dp.png

echo "Captured Japanese lobby at 320dp, 360dp, and 390dp plus English at 320dp."
