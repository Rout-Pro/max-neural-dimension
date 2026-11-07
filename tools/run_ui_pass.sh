#!/usr/bin/env bash
# Emulator-side UI pass, invoked by .github/workflows/ui-screenshot.yml as a
# SINGLE line: android-emulator-runner@v2 splits the workflow `script:` input
# on newlines and runs EACH LINE as its own `sh -c` (src/script-parser.ts +
# main.ts) - multi-line constructs and cross-line variables are impossible
# there (proven run 42225165314). Real bash parses this file directly.
set -euo pipefail

# Never let checked-in or leftover local fixtures shadow device evidence:
# the output directory starts empty on every run (run 42333479189 uploaded
# 1x1 placeholders when the job died before driving).
rm -rf shots
mkdir -p shots

# Run-wide ANR recovery budget, shared by seed_upgrade.py and drive_ui.py:
# the FIRST ANR gets Wait + one app relaunch + fresh UI state; a SECOND ANR
# anywhere in the run stops the job ENV-BLOCKED for review (no storm-riding).
export ND_ANR_STATE=/tmp/nd_anr_recovered
rm -f "$ND_ANR_STATE"

# Production guard: the CI emulator job must never run against the local fake
# adb harness or any adb that is not the real Android Debug Bridge.
if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
  [ -z "${ND_FAKE_STATE:-}" ] || { echo 'REFUSE: ND_FAKE_STATE set in CI job'; exit 1; }
  adb version 2>/dev/null | grep -q 'Android Debug Bridge' || { echo 'REFUSE: adb is not the real Android Debug Bridge'; exit 1; }
fi

# chat-real mode: verify the live backend accepts the app token BEFORE any
# provider call happens. Backend auth check only - /v1/health never calls the
# provider. Stop cheap and early on failure.
if [ -n "${ND_REAL_URL:-}" ]; then
  if ! curl -sf -m 20 -H "Authorization: Bearer ${ND_APP_TOKEN:-}" "$ND_REAL_URL/v1/health" | grep -q '"ok":true'; then
    echo 'BACKEND-HEALTH FAIL: backend unreachable or token rejected - stopping before any provider call'
    exit 1
  fi
  echo 'BACKEND-HEALTH ok: backend reachable and app token accepted (no provider call made)'
fi

# Migration/update gate: seed the DELIVERED v2.0.1, then upgrade in place.
curl -sL -o /tmp/old.apk https://github.com/Rout-Pro/max-neural-dimension/releases/download/v2.0.1/NeuralDimension-2.0.1.apk
adb install /tmp/old.apk
python3 tools/seed_upgrade.py

if [ "${BUILD_TYPE:-debug}" = "release" ]; then
  APK=app/build/outputs/apk/release/NeuralDimension-release.apk
else
  APK=app/build/outputs/apk/debug/NeuralDimension-2.0.apk
fi
adb install -r --no-incremental "$APK"  # plain install: byte-identity gate needs a real on-device file

# Wrong binary under test poisons every later assertion (run 42285108996:
# v2.0.1 left installed, 26-section cascade). Fail fast before driving.
python3 tools/verify_candidate.py --expect-apk "$APK"

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
