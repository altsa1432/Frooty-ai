# FROOTY PART 02 — REAL AI BRAIN

This part upgrades Part 1 from the local placeholder brain to Firebase AI Logic + Gemini.

IMPORTANT:
1. Keep all Part 1 files.
2. Add/replace only the files listed in this ZIP.
3. Before building, create/connect a Firebase project and download google-services.json.
4. Put google-services.json inside the app/ folder.
5. Enable Firebase AI Logic and choose the Gemini Developer API provider in Firebase Console.
6. For local development, configure Firebase App Check debug provider as required by the current Firebase documentation.
7. Do not put a Gemini API key directly into the Android source code.

CURRENT MODEL USED IN THIS PART:
gemini-3.8-flash

The Firebase documentation currently shows this model in its Android Kotlin chat examples. Model availability can change, so if Firebase reports that the model is unavailable, use the current supported text model shown in Firebase AI Logic.

FILES IN THIS PART:
- app/build.gradle.kts
- build.gradle.kts
- app/google-services.json (PLACEHOLDER ONLY — DO NOT USE)
- app/src/main/java/com/frooty/ai/AIService.kt
- app/src/main/java/com/frooty/ai/MainActivity.kt
- README-PART-02.md

FIREBASE SETUP:
A. Firebase Console -> create/open project.
B. Add an Android app.
C. Android package name must match:
   com.frooty.ai
D. Download google-services.json.
E. Replace the placeholder file in app/ with your real downloaded file.
F. Firebase Console -> AI Logic -> Get started.
G. Select Gemini Developer API when asked for the provider.
H. Build the project.

TEST:
Ask:
"मेरा नाम FROOTY है"
then:
"तुम्हारा नाम क्या है?"
The same chat session should preserve the conversation context.

If the build fails, send the exact first red Gradle/Kotlin error. Do not randomly change versions.
