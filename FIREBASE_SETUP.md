# Firebase setup for FROOTY Part 2

## 1. Create Firebase project
Open Firebase Console and create/open the project for FROOTY.

## 2. Add Android app
Register this exact package:
com.frooty.ai

Download `google-services.json`.

Replace the placeholder:
app/google-services.json

with your real Firebase file.

## 3. Enable Firebase AI Logic
In Firebase Console open AI Logic and start the setup.
Use the Gemini Developer API provider for this project.

## 4. App Check
Firebase AI Logic uses App Check protections. For local development, configure the debug provider as described by the current Firebase documentation. Do not ship the debug App Check setup as your production protection.

## 5. Build
Sync Gradle and build the app.

## 6. Test
Ask:
"मेरा नाम FROOTY है"

Then:
"मैंने अभी क्या कहा था?"

The chat session should use the ongoing conversation context.

## Security
Never paste a private Gemini/API credential into Kotlin source.
Firebase AI Logic is designed for client apps and integrates with Firebase security protections.
