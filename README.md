# MAX — Neural Dimension (Android starter)

This is a native Kotlin + Jetpack Compose starter project for the MAX assistant.

## Requirements
- Android Studio with JDK 17
- Android SDK 35
- Gradle sync with internet access

## Open
1. Extract the ZIP.
2. Open the `MAX_Neural_Dimension_Android` folder in Android Studio.
3. Allow Gradle sync.
4. Run the `app` configuration on an Android device or emulator.

## Included
- Purple/violet dark liquid-glass inspired theme
- Home, Chat, Voice, Tools and Settings screens
- Navigation
- Orb visual component
- Local in-memory chat UI
- Backend endpoint configuration UI
- Fast/Thinking mode selection UI

## Important implementation status
This is a compilable foundation, NOT the finished all-features assistant.
The following require additional implementation and testing:
- Secure backend and real AI provider calls
- Room persistence and DataStore persistence
- Speech recognition and text-to-speech
- Wake-word service
- Web development editor and isolated preview
- Image/video generation provider adapters
- Research/search integrations
- Notification listener and authorized reply flows
- Device-control actions and Android permission UX
- Biometric authentication, backup and encryption
- Scheduled tasks and background jobs

Do not put Gemini/OpenAI/Groq secret API keys in the Android APK.
Use an authenticated HTTPS backend for provider requests.

---

## Build & install (this repository)

- GitHub Actions builds the APK and signs it with a persistent release key held in this repo's Actions secrets, so future updates install in place.
- Download the signed APK from **Releases** and install on Android 8.0+.
- Honest status of this build: the purple liquid-glass theme, orb, navigation and all five screens (Home, Chat, Voice, Tools, Settings) work, and chat shows your messages locally. Live AI replies, microphone capture, backend calls and the listed tool modules are **not implemented yet** - the app says so on screen instead of faking them.
