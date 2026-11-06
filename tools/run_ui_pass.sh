#!/usr/bin/env bash
# Emulator-side UI pass, invoked by .github/workflows/ui-screenshot.yml as a
# SINGLE line: android-emulator-runner@v2 splits the workflow `script:` input
# on newlines and runs EACH LINE as its own `sh -c` (src/script-parser.ts +
# main.ts) - multi-line constructs and cross-line variables are impossible
# there (proven run 42225165314). Real bash parses this file directly.
set -euo pipefail

mkdir -p shots

# Migration/update gate: seed the DELIVERED v2.0.1, then upgrade in place.
curl -sL -o /tmp/old.apk https://github.com/Rout-Pro/max-neural-dimension/releases/download/v2.0.1/NeuralDimension-2.0.1.apk
adb install /tmp/old.apk
python3 tools/seed_upgrade.py

if [ "${BUILD_TYPE:-debug}" = "release" ]; then
  APK=app/build/outputs/apk/release/NeuralDimension-release.apk
else
  APK=app/build/outputs/apk/debug/NeuralDimension-2.0.apk
fi
adb install -r "$APK"

if [ "${BUILD_TYPE:-debug}" = "debug" ]; then
  # Debug loop only: pre-launch DataStore byte proof via run-as (release is not
  # debuggable; release preservation is UI-level in the driver). Must run before
  # the first launch - the ND_TEST_HOOK intent rewrites backendUrl on launch.
  adb exec-out run-as com.max.neuraldimension cat files/datastore/max_settings.preferences_pb > /tmp/pb_prelaunch.bin
  if grep -q 'seed-settings-v1' /tmp/pb_prelaunch.bin; then
    echo 'PASS upgrade(pre-launch): seeded backend URL preserved in DataStore'
  else
    echo 'FAIL upgrade(pre-launch): seeded backend URL missing'
    exit 1
  fi
  if grep -q 'SeedUserV1' /tmp/pb_prelaunch.bin; then
    echo 'PASS upgrade(pre-launch): seeded profile name preserved in DataStore'
  else
    echo 'FAIL upgrade(pre-launch): seeded profile name missing'
    exit 1
  fi
fi

python3 tools/drive_ui.py
