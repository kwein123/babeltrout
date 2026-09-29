# Changelog

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
