# Changelog

## Unreleased

### Added
- **Choose your languages.** Tap **✎ Edit** above the push-to-talk buttons: tap a language to remove it (this
  deletes its translation model and built-in voices), or **+ Add language** to add one of 16 more: Catalan,
  Croatian, Czech, Danish, Dutch, Finnish, Hungarian, Indonesian, Italian, Polish, Portuguese, Romanian,
  Slovak, Swedish, Turkish or Vietnamese. English always stays (all translations go through it), and at
  least two languages remain. The menus and Install Assets follow your list.
- **German** (speech, translation, voice), with a hold button next to Hindi. Tap Install assets once to download
  its translation model (~30 MB).
- Language detection recognizes German marker words and ä/ö/ü/ß. "ü" no longer counts as a French clue.
- **Install Assets** also downloads Google's offline voice and (Android 13+) the offline speech recognition
  pack for every language that has one, so the first use of a language doesn't pause. It asks before using
  mobile data. First-run setup still downloads translation models only.

### Changed
- The **Conversation Mic** button turns red while it's on.

### Removed
- The **Auto** push-to-talk button. Google's recognizer can't detect the spoken language online (it fell
  back to English), so every language now has its own button.

## 1.5 (versionCode 6) — 2026-09-30

### Build
- Android Gradle Plugin 9.1.0 → 9.4.1, Gradle 9.3.1 → 9.8.0 (wrapper download checksum pinned), Kotlin 2.2.10 → 2.4.20.
  The app now ships Kotlin standard library 2.4.20.
- Build-tool libraries with known CVEs (Bouncy Castle, jose4j, jdom2, commons-lang3, httpclient) are pinned to
  patched versions in the root `build.gradle.kts`, and for Android Lint's own runtime (`androidLintTool`) in
  `app/build.gradle.kts`. None of these ship in the app. AGP 9.4 also drops the Netty-based device-test tooling.
  Together these clear the 51 build-tooling Dependabot alerts.

## 1.4 (versionCode 5) — 2026-09-29

### Security
- ML Kit translate 17.0.3 (still the latest) bundled OkHttp 3.0.0 and Okio 1.6.0. They are now forced to
  OkHttp 4.12.0 and Okio 3.6.0, fixing CVE-2021-0341, CVE-2016-2402 and CVE-2023-3635.

## 1.3 (versionCode 4) — 2026-09-29

### Added
- **Hands-free conversation** (Android 13+, default on): Babeltrout keeps its own microphone open and
  uses the Silero voice-activity detector (bundled, 644 KB) to find turns. There are no recognizer
  restarts, so no beeps and no gaps, and no length limit. The mic is muted while a translation is spoken.
- Each turn is recognized **twice in parallel**, by recognizers locked to each conversation language, and
  `TurnLanguageChooser` picks the real one by script, confidence, ML Kit text check, marker words, and
  turn-taking. This fixes single words ("no speech recognized") and the second language being heard as
  the first. Recognizer confidence scores that turn out to be fixed placeholders are ignored.
- Falls back automatically to the classic recognizer mic if a phone's recognizer rejects app audio.
- **Built-in voices**: Babeltrout now runs Piper voices itself (sherpa-onnx 1.13.8). No SherpaTTS needed.
- **Voice Library** (Setup): download any of 5 Farsi voices (amir, gyro, ganji, ganji_adabi, reza_ibrahim)
  and 3 Hindi voices, in compact (~21 MB) or full-quality (~67 MB) versions; use, switch or delete them.
  Downloads are checked against pinned SHA-256 digests and unpacked with path-traversal and symlink protection.
- Choose Voices lists built-in voices alongside system voices; Farsi automatically prefers a built-in voice.
- Warns before downloading voices over mobile data.

### Changed
- Release APKs are split per CPU type (arm64-v8a ~47 MB, armeabi-v7a ~33 MB).
- Farsi diagnostics treat SherpaTTS as optional when a built-in voice is installed.

## 1.2 (versionCode 3) — unreleased

### Added
- Hindi (speech, translation, voice, Devanagari transliteration).
- **Choose Voices**: per-language voice selection across all installed TTS engines, with a spoken sample.
- **Long-speech mode** for conversation (Android 13+ segmented recognition sessions).
- Pronunciation lines for Ukrainian, Russian and Hindi targets.
- Settings persist across launches.
- Farsi diagnostics list every Farsi voice found and the chosen one.
- Unit tests (38) and GitHub Actions CI and release workflows.
- Docs: ARCHITECTURE, FARSI_VOICES, REVIEW-2026.

### Changed
- Farsi transliteration uses a common-word lexicon plus vowel rules instead of a letter-by-letter map.
- One translation call per phrase (ML Kit pivots through English internally).
- Model install downloads one model per language.
- Release builds: R8 shrinking and phone-only ABIs (APK about 75 MB → 31 MB).

### Fixed
- Conversation mode discarded speech when the recognizer ended in an error or timeout.
- Conversation mode stopped spoken replies after 15 seconds.
- Voices reported with ISO-639-2 codes (e.g. `fas`) were not matched to their language.
- French phrases containing "la" were detected as Spanish.

### Security
- App data excluded from cloud backup and device transfer.
- No longer takes persistable URI permissions for picked files.

## 1.1 (versionCode 2)
- SherpaTTS routing for Farsi, Farsi TTS diagnostics, conversation mode, icon options.

## 1.0 (versionCode 1)
- Push-to-talk translation between English, Farsi, Ukrainian, Russian, Arabic, French and Spanish.
