# Neural Dimension — Continuation Handoff (2026-11-06)

State anchor: main @ 4eb2978. Repo public; delivery = GitHub release APK. NO secrets in this repo ever (provider keys server-side only in the Cloudflare Worker backend/).

## Delivered
- v2.0.1 (tag v2.0.1, versionCode 3) is the ONLY published release. Direct APK: releases/download/v2.0.1/NeuralDimension-2.0.1.apk. Published 2026-11-06. Install/user-test status unconfirmed.
- Everything after that is UNPUBLISHED on main. Current head versionCode 4.

## Implemented since v2.0.1 (all on main, CI Build green incl. JVM unit tests)
- Phase 2: chat rename/search/edit/regenerate/attachments/incognito/error rendering/delete hygiene (mock-backed).
- Phase 3 voice: on-device recognizer preference (API 31+, honest "may use network" label), TTS voice picker + persistence, mic-denial settings deep link.
- Phase 4 Web Studio (Part 15): local projects (Blank/Starter/React-CDN), editor, sandboxed WebView preview (no JS bridge, file/content access off, external top-level nav blocked), console capture, single-file export via MediaStore (combineDocument inlines local css/js, preserves script attrs, CDN stays external), .html import.
- Phase 5 (Part 18): open-apps launcher, camera/notifications/Wi-Fi/Bluetooth shortcuts, media volume slider, battery/storage/RAM/model/uptime diagnostics, flashlight.
- Phase 6 (Part 17): Notes, Reminders (WorkManager -> real notification), Habits (streaks), Expenses (INR BigDecimal parsing), Weather (Open-Meteo, keyless), Daily briefing. Room DB now v4 (migrations 1->2->3->4, no destructive fallback).

## Verification gates (status)
VERIFIED (emulator evidence): studio export byte-E2E incl. React CDN pass-through; console capture; project CRUD; RAM/diagnostics/apps/volume/camera-shortcut rendering; note add+list; briefing aggregation; reminder permission dialog invocation.
VERIFIED (unit): combineDocument inlining/attribute preservation (4 tests); Money.parseRupeesToCents decimal edge cases (12 tests).
OPEN: reminder listing/firing/actual notification; habit streak & expense total state changes; weather real fetch; intent invocation/effects for shortcuts & open-apps; upgrade preservation gate end-to-end.
BLOCKED once by environment: emulator ANR storms (ENV-BLOCK pattern in drive_ui.py).

## Upgrade/migration gate (release blocker)
Workflow seeds DELIVERED v2.0.1 via tools/seed_upgrade.py (task, chat message, profile name, backend URL on .invalid TLD), then adb install -r the new build; drive_ui.py section 'upgrade' asserts preserved fields via run-as DB pull + DataStore bytes + UI. First execution 2026-11-06 FAILED AT SEED: all taps missed (app render/dump unverified; seed script lacks the ANR-settle + screenshot-on-failure handling that drive_ui.py has). FIX APPLIED (post-handoff commit): seed_upgrade.py now has settle_and_verify_launch() with ANR-storm riding, foreground verification via dumpsys, failure screenshots uploaded via the shots/ artifact, and ENV-BLOCK exit code 2 distinct from seed-failure 1. Next pass just re-runs it.
versionCode MUST exceed 3 (v2.0.1) for upgrades to install — head has 4; keep the invariant.

## Release-evidence requirements before ANY new release
1. Release APK artifact assertions: debuggable=false, release build, hook-free (nd_test_hook), HTTPS-only, versionCode > 3, same signing as v2.0.1 (install -r success proves it). Check code exists in Build workflow (aapt/apksigner greps — note aapt prints `=(type 0x12)0x0` and cert digests print lowercase without colons; test greps against real output).
2. install-r over delivered v2.0.1 with UI-visible preserved task, picker-opened conversation with its messages, settings screen values — on the RELEASE APK (no run-as; release is not debuggable).
3. Mock/local features labeled distinct from real AI/mic capabilities in user-facing copy.
4. New-executor note: repair test preparation as needed, but no repeated broad emulator cycles.
5. Live-provider verification is QUOTA-BLOCKED (429s since 2026-11-05); no probes, no billing/model changes without owner approval.

## Known emulator/driver lessons
- WebView text is invisible to uiautomator dumps (preview assertions need pixels).
- Physical IME typing drops chars; uiautomator escapes & as &amp;; tap exact-match dialog buttons (substring 'Allow' hits dialog titles).
- drive_ui.py has ensure_visible() (small swipes), settle/ANR handling; seed_upgrade.py does NOT (fix queued).
- Emulator: API 34, -noaudio -gpu swiftshader_indirect; SpeechRecognizer untestable; launcher ANR storms are environmental — settle/abort, never drive through.

## Blueprint scope remaining (25-part build prompt)
Parts 16 (creative AI - provider-blocked except local docs), 17 remainder (calendar integration, quiet hours, background jobs, notifications polish), 19 (security/privacy), 20 (settings center), 22 (full entity set), 23 (performance tiers), 24 (final testing), 25 (deliverables checklist). Robot 3D realism gap openly unresolved.
