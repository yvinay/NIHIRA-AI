# NIHIRA – Phase 1 + 2

A voice assistant for Android that understands you, asks your approval, then acts.

## Setup (about 30 minutes, first time)

1. Install **Android Studio** (free): https://developer.android.com/studio
2. Unzip this folder and choose **File → Open → NIHIRA** in Android Studio. Let Gradle sync finish (first sync downloads a lot; accept any SDK install prompts).
3. **Get a Gemini API key** (free tier available) at https://aistudio.google.com/apikey
4. Open `local.properties` in the project root (Android Studio creates it) and add:
   ```
   GEMINI_API_KEY=your-key
   GEMINI_MODEL=gemini-3.8-flash
   ```
   (Building on GitHub instead? Add the key as a repository secret named `GEMINI_API_KEY`.)
   Without a key, NIHIRA still works in **Phase 1 basic mode** (fixed commands, offline matching).
5. On your phone: **Settings → About phone → tap "Build number" 7 times** → Developer options → turn on **USB debugging**. Connect with USB.
6. Press the green ▶ **Run** button. Allow microphone, contacts and phone permissions.

Do the same for the OnePlus. On OnePlus, also go to **Settings → Battery → NIHIRA → Don't optimize** (needed later for Phase 3).

## Try saying

| You say | NIHIRA does |
|---|---|
| "Open YouTube" | Opens the app |
| "Call Amma" | Asks approval, then calls |
| "Tell Ravi on WhatsApp I'll be 10 minutes late" | Asks approval, opens WhatsApp with the message typed |
| "Wake me up at 6:30 tomorrow" | Sets an alarm |
| "Timer for 10 minutes and turn on the torch" | Does both |
| "Take me to Kempegowda Airport" | Starts Maps navigation |
| "Play Arijit Singh on YouTube" | Searches YouTube |
| "Volume 40" / "Open Bluetooth settings" | Adjusts / opens settings |
| "What's 18% of 2,450?" | Answers by voice (smart mode only) |

Approve by saying **yes / okay / haan / houdu**, reject with **no / cancel / nahi / beda**, or tap the buttons. No answer in 30 seconds = cancelled.

## How it's built

| File | Role |
|---|---|
| `VoiceEngine.kt` | Ears (SpeechRecognizer, en-IN) and mouth (TextToSpeech) |
| `CommandRouter.kt` | Phase 1 offline keyword commands + yes/no parser |
| `Brain.kt` | Shared brain instructions; picks Gemini (default) or Claude |
| `GeminiBrain.kt` | Phase 2: Gemini API with function calling |
| `ClaudeBrain.kt` | Optional alternative brain (Claude) |
| `Tools.kt` | List of actions, their risk level, and tool definitions |
| `ActionExecutor.kt` | Resolves apps/contacts and performs actions via Android intents |
| `MainActivity.kt` | Loop: listen → understand → approve → act → speak |

**Approval rules:** calls and messages *always* ask. Everything else asks only while "Ask my approval before every action" is on (default on).

**Adding a new action:** add its definition and risk in `Tools.kt`, then `describe` + `execute` cases in `ActionExecutor.kt`.

## Known limits (fixed in later phases)

- WhatsApp/SMS open with your message ready, but **you tap Send** → Phase 4 (Accessibility Service) will press it.
- You must tap the mic → Phase 3 adds the "Hey Nihira" wake word.
- Each command is independent (no memory of the previous one) → Phase 5.

## Security

Your API key is built into the APK. That's fine for your own phones, but **never share the APK or commit `local.properties`**. If you enable billing on your Gemini key, set a budget alert in Google Cloud.
