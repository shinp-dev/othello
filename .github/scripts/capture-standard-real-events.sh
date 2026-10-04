#!/usr/bin/env bash
set -euo pipefail

mkdir -p screenshots
adb shell wm size "390x844"
adb shell wm density 160

for locale in ja en; do
  adb shell am force-stop com.shinpstudio.chanriva || true
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.example.othello.StandardRealEventsScreenshotTest \
    -Pandroid.testInstrumentationRunnerArguments.captureWidth=390 \
    -Pandroid.testInstrumentationRunnerArguments.captureLocale="$locale"
  adb pull \
    "/sdcard/Download/ChanrivaPreviews/standard-real-events-${locale}-390dp.png" \
    "screenshots/standard-real-events-${locale}-390dp.png"
  test -s "screenshots/standard-real-events-${locale}-390dp.png"
done
