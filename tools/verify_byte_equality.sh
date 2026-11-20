#!/usr/bin/env bash
# Byte-equality gate: the APK Android installs on this emulator must be
# byte-identical to the signed release artifact. adb install copies the file
# unchanged, so the installed base.apk sha256 must equal the artifact sha256.
set -euo pipefail

APK=$(ls apk/NeuralDimension-*.apk)
echo "artifact: $APK"
EXPECTED=$(sha256sum "$APK" | cut -d' ' -f1)

adb install "$APK"

DEVICE_PATH=$(adb shell pm path com.max.neuraldimension | head -1 | sed 's/package://' | tr -d '\r')
echo "device path: $DEVICE_PATH"
adb pull "$DEVICE_PATH" installed.apk

ACTUAL=$(sha256sum installed.apk | cut -d' ' -f1)
echo "expected=$EXPECTED"
echo "actual=$ACTUAL"
if [ "$EXPECTED" != "$ACTUAL" ]; then
  echo "::error::installed bytes differ from the signed artifact"
  exit 1
fi
echo "OK: installed bytes match the signed artifact exactly"
