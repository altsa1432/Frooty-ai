# FROOTY Android assistant

FROOTY is an Android Kotlin assistant with Hindi/Hinglish chat, voice input/output,
Firebase AI Logic chat, and user-controlled local memory.

## Current status

- [x] Parts 1–3 foundation: chat, Hindi voice input/output, orb UI, and Firebase
  AI Logic chat code. No separate Part 3 package/release artifact is tracked.
- [x] Part 4 — Memory: say “याद रखो कि मेरा नाम Naresh है” to save a fact, use
  **MEMORY** to review/remove facts, or say “सब भूल जाओ” to clear all.
- [x] Part 5 — Foreground wake phrase prototype: **START WAKE WORD** listens
  for “Hey FROOTY” only while the app is open, with consent and a visible stop.
- [x] Part 6 — Android system assistant: **SET AS SYSTEM ASSISTANT** opens
  Android default-app settings. Once selected, the device assistant gesture can
  launch FROOTY and start voice input; the button shows whether FROOTY is active.
- [x] Real video generation UI/backend: **CREATE AI VIDEO · VEO** submits prompt
  to Veo 3.1 through authenticated, App-Check-protected Firebase Functions,
  polls operation status, previews the MP4, and can save it. No daily cap is
  configured by request; paid Veo usage and Storage costs may apply.
- [ ] Video backend connection: enable Anonymous Auth, App Check, Firestore,
  Storage and Functions; add the `GEMINI_API_KEY` secret and deploy (see
  [FIREBASE_SETUP.md](FIREBASE_SETUP.md)).
- [x] Part 7 — screen understanding: after an explicit explanation/consent,
  enable FROOTY in Android Accessibility Settings. Visible text from other apps
  stays on-device until the user taps **Ask FROOTY**; password fields are
  skipped. This reads accessible text, not pixels or image-only content.
- [ ] Part 8 — Phone actions.
- [ ] Parts 9–11 — Live data, advanced features, and final device testing.

Memory is stored privately in the app's SharedPreferences on this device.
It is not synced or automatically inferred from conversations. Relevant saved
facts are included in messages sent to the configured AI provider.
The wake phrase prototype uses Android's speech-recognition provider, which may
process audio remotely; it is not an offline hotword engine and does not listen
while FROOTY is closed. System assistant setup still requires selecting FROOTY
in Android settings and granting microphone permission when asked. Neither
feature bypasses Android permissions or provides a guaranteed custom
background “Hey FROOTY” hotword.

## Build

Open this repository in Android Studio or run `gradle assembleDebug` with JDK 17,
Gradle 8.13, and Android SDK 36 installed. The Android module, Kotlin sources,
manifest, and resources are under `app/src/main/`.

## Firebase setup still required

The app module uses the `com.frooty.ai` client from
`app/google-services.json` through the Google Services Gradle plugin. If you
connect another Firebase project, replace it with a config containing that
exact Android package. Firebase AI requests may still require App Check setup
and provider enablement; local memory works independently.

See [FIREBASE_SETUP.md](FIREBASE_SETUP.md) for setup instructions.
