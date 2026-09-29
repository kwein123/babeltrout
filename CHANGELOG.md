# Changelog

## 1.3 (versionCode 4) — unreleased

### Added
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
