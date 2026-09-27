#!/usr/bin/env bash
set -euo pipefail

mkdir -p screenshots
adb shell 'rm -f /sdcard/Download/ChanrivaPreviews/people-play-room-*.png'
for language in ja en; do
  for width in 320 360 390; do
    adb shell wm size "${width}x800"
    adb shell wm density 160
    adb shell am force-stop com.shinpstudio.chanriva || true
    ./gradlew :app:connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=com.example.othello.PeoplePlayRoomScreenshotTest \
      -Pandroid.testInstrumentationRunnerArguments.captureWidth="$width" \
      -Pandroid.testInstrumentationRunnerArguments.captureLanguage="$language"
    adb pull \
      "/sdcard/Download/ChanrivaPreviews/people-play-room-${language}-${width}dp.png" \
      "screenshots/people-play-room-${language}-${width}dp.png"
    test -s "screenshots/people-play-room-${language}-${width}dp.png"
  done
done

echo "Captured room screenshots for Japanese and English at 320dp, 360dp, and 390dp."
