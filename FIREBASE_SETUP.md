# Firebase setup for FROOTY

## 1. Create Firebase project
Open Firebase Console and create/open the project for FROOTY.

## 2. Add Android app
Register this exact package:
com.frooty.ai

Download `google-services.json`.

The repository's config is in `app/google-services.json` and includes the
`com.frooty.ai` app client. The Google Services plugin is already enabled for
the app module. If you replace the Firebase project, download a config that
includes the exact `com.frooty.ai` package name.

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

## Real AI video generation — Veo 3.1

The Android app uses two authenticated Firebase callable functions; the Veo API
key is held as a Cloud Functions secret and is never shipped in the APK. There
is intentionally **no daily generation limit**. Veo generation and video
storage can incur charges; consider setting Google Cloud budget alerts.

Before deploying:

1. Upgrade the Firebase project to the Blaze plan and enable Cloud Functions,
   Cloud Firestore, and Cloud Storage. Create the default Firestore database
   and a Storage bucket in the Firebase console.
2. In Firebase Authentication, enable the **Anonymous** sign-in provider.
3. Register the Android app in App Check. Debug builds use the App Check debug
   provider; copy its token from Logcat and register that token in Firebase
   Console. Production builds use Play Integrity and need its Firebase/Play
   Console configuration. Do not ship a debug token.
4. Create a Gemini API key with Veo 3.1 access and save it as a Firebase secret:

   ```sh
   firebase functions:secrets:set GEMINI_API_KEY
   ```

   Enter the key only at the secure prompt; never commit it or put it in Kotlin.
5. Ensure the Cloud Functions runtime service account can use Firestore, write
   generated objects in Cloud Storage, and sign V4 URLs (grant the required
   Firestore/Storage roles and `Service Account Token Creator` to the runtime
   service account). Video links expire after 15 minutes.
6. From the repository root, install and deploy:

   ```sh
   npm ci --prefix functions
   firebase deploy --only firestore:rules,storage,functions
   ```

The Firestore and Storage rules deny direct client access. Callable functions
require both Firebase Authentication and App Check; the backend records each
video operation's anonymous user so a different account cannot retrieve its
result. Prompt length is validated server-side. There is no daily usage cap by
user request, so use Cloud Billing budgets/alerts and monitor Veo usage.

The current project config is selected by `.firebaserc`. If you use a different
Firebase project, also update `.firebaserc` and replace
`app/google-services.json` with that project's Android config before deploying.

The app's **CREATE AI VIDEO · VEO** button accepts a text prompt, submits a real
`veo-3.1-generate-preview` request, checks completion, previews the MP4, and
lets the user save it. Veo normally generates a short video (about 8 seconds);
availability and price depend on Google's account, region, and current model
terms. The feature cannot generate until the project setup and deployment above
are completed.
