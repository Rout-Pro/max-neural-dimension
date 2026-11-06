# Phase 3 - Live voice conversation (blueprint Parts 5, 6, 14)

## Already built (verified earlier phases)
- VoiceEngine: Android TextToSpeech with WAV synthesis, amplitude-envelope mouth
  animation synced to real playback, fallback direct-speak path.
- SpeechRecognizer scaffolding: availability check, listening flags, partial/final/error
  callbacks.
- RobotState LISTENING/THINKING/SPEAKING states exist in the GL state machine.
- RECORD_AUDIO in manifest.
- Voice tab screen with "Test MAX voice" (TTS verified on emulator via screenrecord
  + logcat envelope timeline).

## Phase 3 scope (this phase)
1. **Live conversation loop** (Part 5 flow 1-10):
   Talk toggle -> LISTENING (SpeechRecognizer start) -> final text -> THINKING ->
   send through existing secure-backend chat path -> SPEAKING (TTS reply, mouth
   synced) -> back to LISTENING for continuous conversation. Interrupt: user taps
   or starts talking -> stop TTS + resume listening. End conversation button.
2. **Runtime mic permission flow**: request on first Talk activation, honest
   rationale + settings deep-link fallback on denial; visible mic indicator while
   listening (Part 14 wake-word section: explicit activation only, no background
   listening - matches "explicit activation / user-controlled shutdown").
3. **Voice controls** (Part 14): voice selection from device TTS voices
   (male/female where the device exposes them), speech rate slider (engine already
   has setRate), language selection English/Hindi (+ Hinglish via hi-IN voice
   where supported), persisted in SettingsRepository.
4. **VAD**: end-of-speech auto-send via SpeechRecognizer onEndOfSpeech +
   silence timeout; no always-on background operation (battery).
5. **Interruption + mute**: mute mic toggle pauses recognizer; barge-in stops TTS
   (engine stop + state to LISTENING).
6. **Error handling**: honest states for recognition unavailable, no mic
   permission, backend failure (reuse chat error wording) - no simulated
   conversation (Part 5 explicit).

## Verification plan (zero provider burn)
- Emulator limits: no mic hardware; SpeechRecognizer untestable end-to-end
  (known emulator constraint). Verified on emulator: permission flow UI, state
  machine transitions via test hook, TTS envelope sync (existing), interruption
  state changes, settings persistence.
- Mock-backed driver sections: state sequence LISTENING->THINKING->SPEAKING
  asserted via UI state badges; interruption test stops SPEAKING.
- Real-mic STT: flagged for on-device verification by the user (their Vivo T4x),
  never claimed from emulator evidence.

## Explicitly out of scope / gated
- No cloud STT/TTS providers (paid, keys) - on-device Android APIs only.
- No wake-word background service in this phase (explicit activation only).
- No provider-call verification until quota recovers (separately gated).
