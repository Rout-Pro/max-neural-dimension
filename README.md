# MAX - Neural Dimension

Native Android AI companion (Kotlin + Jetpack Compose, minSdk 26). Purple liquid-glass design with a real-time 3D humanoid robot, voice conversation and streaming AI chat.

## Version 2.0 - Phase 1 (current)

Working and verified on an emulator (Pixel 6, API 34):

- **3D humanoid robot** - custom OpenGL ES 3.0 renderer (an equivalent 3D engine to Filament, chosen to keep the APK small and dependency-free). Procedurally built, hierarchically rigged character: silver body, purple glass faceplate, glowing eyes with blinking, animated mouth, head/neck/shoulder motion, hologram ring, particle field, emissive glows.
- **Animation state machine** - IDLE / LISTENING / THINKING / SPEAKING / HAPPY / CURIOUS / CONFUSED / EXCITED / ERROR, all driven by real app events.
- **Lip-synced voice** - Android TTS is synthesized to a WAV file, its real amplitude envelope is measured, and the mouth animates from that envelope while the audio plays through MediaPlayer.
- **Live voice conversation** - SpeechRecognizer (partial + final results), interrupt, mute, continuous mode, language selection (en-IN / en-US / hi-IN), speech-rate control.
- **Streaming AI chat** - Gemini, OpenAI-compatible and Groq providers with SSE streaming, Fast/Thinking model selection, connection test. API keys are stored in Android Keystore-encrypted storage on the device, never in source.
- **Chat history** - Room database, multiple conversations, rename/delete, copy, markdown + code rendering.
- **Holographic panels** - floating glass panels (AI status, tasks, alerts, today, actions, memory) fed by real app data.
- **Tasks + memory** - Room-backed task list, on-device memory facts (added to the AI system prompt, user-controlled).
- **Device tools** - flashlight, battery, storage, volume, Wi-Fi/Bluetooth shortcuts.
- **Settings** - profile, providers, personality (Professional / Sweetheart), voice, robot animation intensity, reduced motion, memory, privacy notes.

## Honest limitations (not implemented yet)

- No API key is bundled: live AI replies require the user's own provider key (Settings > AI Provider).
- Keys live on-device; the blueprint's "secure backend" needs server hosting - a later phase.
- Image/video generation, deep research, web development studio: Phase 3, needs provider APIs.
- Reminders/notifications/calendar/weather, app PIN/biometric lock: Phase 2.
- Speech recognition requires Google speech services on the device; TTS voice depends on the installed TTS engine.

## Build

GitHub Actions builds and signs the APK with the persistent release key (ND_* repo secrets), so updates install in place.

- Build + sign: `.github/workflows/android.yml`
- Emulator UI verification with screenshots: `.github/workflows/ui-screenshot.yml` (`tools/drive_ui.py`)

Releases: https://github.com/Rout-Pro/max-neural-dimension/releases
