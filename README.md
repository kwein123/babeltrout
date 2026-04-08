# babeltrout (Android)

Android app with the same core workflow as Kevin Un-Babel:
- Hold-to-talk buttons for auto-detect and fixed source languages.
- Source options: English, Farsi, Ukrainian, Russian, Arabic, French, Spanish.
- Target options: English, Farsi, Ukrainian, Russian, Arabic, French, Spanish.
- Translation route tries direct source->target first, then falls back to English pivot only when needed.
- Text-to-speech playback in target language.
- For Farsi/Arabic targets, adds an approximate pronunciation transliteration line in source-script style.
- Export transcript history to `.txt`.

## What is preinstalled vs downloaded
- App code and transliteration logic are installed with the APK.
- Offline speech recognition and translation models are downloaded on first run (or via `Install Missing Assets`).
- Why: Android speech/ML model files are large and are not bundled by default with ML Kit speech/translation APIs.

## First-time setup on your Mac
1. Install Android Studio: https://developer.android.com/studio
2. Open Android Studio once and let it install SDK components.
3. In Android Studio, open this folder:
   `/Users/kevinweinrich/Downloads/babeltrout`
4. Wait for Gradle sync to finish.

## Signed release checklist (recommended)
This creates an installable signed APK you can keep and reuse.

1. Create your release keystore once (Terminal on Mac):

```bash
cd /Users/kevinweinrich/Downloads/babeltrout
keytool -genkeypair \
  -v \
  -keystore release-keystore.jks \
  -alias babeltrout \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

2. Create signing properties file:

```bash
cd /Users/kevinweinrich/Downloads/babeltrout
cp keystore.properties.example keystore.properties
```

3. Open `keystore.properties` and set:
   - `storeFile` (for example `release-keystore.jks`)
   - `storePassword`
   - `keyAlias` (for example `babeltrout`)
   - `keyPassword`

4. Build signed APK in Android Studio:
   - Menu: `Build` -> `Generate Signed Bundle / APK...`
   - Choose `APK` -> `Next`
   - If prompted, select:
     - Key store path: `/Users/kevinweinrich/Downloads/babeltrout/release-keystore.jks`
     - Key alias/password values
   - Select `release` build variant
   - Click `Finish`

5. Locate output APK:
   - Android Studio notification -> `locate`
   - Usual path:
     `/Users/kevinweinrich/Downloads/babeltrout/app/release/app-release.apk`

6. Keep these safe:
   - `release-keystore.jks`
   - `keystore.properties` values
   If you lose them, you cannot update the app with the same signing identity later.

## Install on your Android phone (USB, easiest)
1. On your phone, enable Developer Options:
   - Open `Settings` -> `About phone`
   - Tap `Build number` 7 times
   - Enter your PIN if prompted
2. Enable USB debugging:
   - Open `Settings` -> `System` -> `Developer options`
   - Turn on `USB debugging`
3. Connect phone to Mac by USB cable.
4. On phone, accept the `Allow USB debugging` prompt.
5. In Android Studio:
   - Top bar device picker: select your phone.
   - Click `Run` (green triangle).
6. Android Studio builds and installs the app automatically.

## First launch on phone
1. Open `Babeltrout`.
2. Grant microphone permission when prompted.
3. Keep internet enabled.
4. Tap `Install Missing Assets` once and wait until it finishes.
5. After install, normal use is offline for supported speech/translation models.

## Install SherpaTTS + Persian Piper voice (from phone)
1. Copy these files to your phone (same folder is easiest):
   - `SherpaTTS-*.apk`
   - `fa_IR-amir-medium.onnx`
   - `fa_IR-amir-medium.onnx.json`
2. Open `Babeltrout`.
3. Tap `Install TTS APK` and pick the SherpaTTS APK.
4. If Android prompts for unknown-app installs:
   - Allow installs for Babeltrout.
   - Return to Babeltrout and tap `Install TTS APK` again.
5. After SherpaTTS installs, tap `Open SherpaTTS` once.
6. Back in Babeltrout, tap `Import Voice Files` and select both Piper files (`.onnx` and `.onnx.json`).
7. In the share/import flow, choose SherpaTTS and complete import there.
8. Tap `Check Voices` in Babeltrout and verify Farsi shows as available.
9. Optional: tap `Voice Settings` if you want to inspect Android engine settings manually.

## Use
1. Pick `Target language`.
2. Use `Setup & diagnostics` link (top-right) only when you need install/check/export tools.
3. Hold one of the talk buttons:
   - `Auto` for detection.
   - Or a fixed source button (faster, skips language detection).
4. Release to process.
5. App shows:
   - Target translation
   - Transliteration line (for Farsi/Arabic target)
   - Source transcript
6. TTS engine routing is automatic:
   - Farsi output prefers SherpaTTS.
   - Non-Farsi output prefers Google TTS.
   - If preferred engine is unavailable, Babeltrout falls back to the default Android engine.
   - You do not need to manually switch Android's preferred engine for each utterance.
7. Tap `Speak` on any entry to replay target audio.
8. Use `Output text size` slider to increase readability.
9. Tap `Conversation` on the main page for two-way conversation mode:
   - Select two languages (`Language A` and `Language B`).
   - Tap `Conversation Mic: OFF/ON` to start/stop continuous listening.
   - Use `Auto-restart mic when idle`:
     - ON: keeps listening after silence (may cause periodic recognizer beeps on some devices).
     - OFF: stops conversation mic after idle timeout (no repeat idle beeps).
   - Each detected phrase is auto-routed from spoken language to the other language.
   - Output includes source text, translated text, transliteration of translated text in the source script, and spoken target audio.
10. In `Setup & diagnostics` page:
   - Tap `Check Downloads` to verify ML translation models.
   - Tap `Check Voices` to verify installed TTS voices.
   - Tap `Diagnose Farsi TTS` for an automated end-to-end Farsi voice check and fix list.
   - Tap `Icon Preview` to compare launcher icon options inside the app.
   - Tap `Voice Settings` to install or switch voice engines.
   - Tap `Export TXT` to save transcript as a text file.

## Troubleshooting
- No recognition results:
  - Verify mic permission is granted.
  - Try fixed-language hold button instead of auto-detect.
- Translation fails for a pair:
  - Tap `Install Missing Assets` again with internet on.
- TTS sounds wrong or missing:
  - Install language voice packs in Android system `Text-to-speech` settings.

## Build APK manually (optional)
1. In Android Studio: `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`.
2. When complete, click `locate` to open APK folder.
3. Copy APK to phone and open it.
4. If blocked, allow installs from this source on phone.

## Install signed APK on phone (without Android Studio after build)
1. Copy `/Users/kevinweinrich/Downloads/babeltrout/app/release/app-release.apk` to your phone.
2. On phone, open the APK from Files app.
3. If prompted, allow app installs from that source.
4. Tap `Install`.
5. Open `Babeltrout`, grant microphone permission, and tap `Install Missing Assets` once.

## Icon options
Alternative trout icons are included:
- `/Users/kevinweinrich/Downloads/babeltrout/app/src/main/res/drawable/ic_babeltrout_alt_reef.xml`
- `/Users/kevinweinrich/Downloads/babeltrout/app/src/main/res/drawable/ic_babeltrout_alt_rainbow.xml`
- `/Users/kevinweinrich/Downloads/babeltrout/app/src/main/res/drawable/ic_babeltrout_alt_sunset.xml`

To select one, edit:
- `/Users/kevinweinrich/Downloads/babeltrout/app/src/main/AndroidManifest.xml`

Change both `android:icon` and `android:roundIcon` in the `<application>` block to your chosen drawable, for example:
- `@drawable/ic_babeltrout_alt_reef`
