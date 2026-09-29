# Babeltrout review — September 2026

A review of the app as written in early 2026, what this pass changed, and a recommended path forward.
Answers to the specific questions are in [Questions](#questions).

---

## What this pass changed (v1.2)

**New**
- **Hindi**, with a hold button, recognition, translation, speech, and Devanagari ↔ Latin transliteration.
- **Choose Voices**: pick any installed voice per language across all TTS engines (for example several
  Farsi Piper voices), with a spoken sample. Remembered across launches.
- **Long-speech conversation mode** (Android 13+): one recognition session spans natural pauses.
- **Pronunciation lines for every non-Latin target** (Ukrainian, Russian, Hindi, Arabic, Farsi), not
  just Farsi and Arabic.
- Settings are remembered: target language, conversation pair, speech rate, text size.

**Fixed**
- Conversation mode **threw away speech** whenever the recognizer ended in an error or timeout. Partial
  results are now kept and translated.
- Conversation mode **cut off spoken replies after 15 seconds**. The timeout now scales with text length,
  and long text is chunked to the engine's input limit.
- TTS voices reported with three-letter codes (`fas`, as SherpaTTS may do) weren't recognized as Farsi.
- French phrases containing "la" were detected as Spanish.
- Farsi transliteration was a consonant skeleton ("slam" for سلام). It now uses a common-word lexicon
  plus vowel rules ("salaam", "khaane", "istgaah"), normalizes Arabic ي/ك to Persian ی/ک, and handles
  the silent vav in خوا and ZWNJ prefixes (می‌).
- Translation errors on non-English pairs retried the same pivot a second time. ML Kit already pivots
  through English internally, so each phrase is now one call.
- Model install made 12 pair-by-pair checks. It now downloads one model per language, with progress.

**Security and privacy**
- `allowBackup=false` plus data-extraction rules: settings and anything else the app holds stay out of
  Google cloud backup and phone-to-phone transfer.
- Stopped taking *persistable* URI grants on every picked APK or voice file. Those grants accumulated
  forever; a one-time grant is all the flow needs.
- Documented why each permission exists, and flagged `REQUEST_INSTALL_PACKAGES` for removal before any
  Play Store release.
- CI release workflow keeps the signing key in GitHub Secrets and deletes it after the build.

**Performance and size**
- Release builds use R8 shrinking (app code dropped from about 16 MB to 1.6 MB) and ship only phone CPU
  architectures. **Release APK: about 75 MB → 31.5 MB.**
- Translator model readiness is cached, so each phrase no longer pays a "download if needed" round-trip.
- Translator cache is thread-safe (it's used from the IO dispatcher).

**Engineering**
- Pure logic extracted from the 3,000-line `MainActivity` into `Languages`, `ScriptHeuristics`,
  `TransliterationEngine` and `SpeechText`, covered by **38 unit tests**.
- GitHub Actions: tests and a debug APK on every push; a signed APK attached to a GitHub Release on each `v*` tag.
- README rewritten (the old one pointed at `~/Downloads/babeltrout`), plus [ARCHITECTURE.md](ARCHITECTURE.md)
  and [FARSI_VOICES.md](FARSI_VOICES.md).

**Not yet verified on a phone.** Everything compiles and the unit tests pass, but the Android-side
changes (segmented sessions, voice picker, R8 release build) need a hands-on check. See
[Test plan](#test-plan).

---

## Questions

### Should this become a GitHub package?

**No, but it should become a GitHub *Release*.** "GitHub Packages" hosts *libraries* (Maven, npm and
so on) for other code to depend on. An app is distributed as an APK, and GitHub Releases is the right
home for it:

- `release.yml` (added) builds a signed APK when you push a tag such as `v1.2` and attaches it and a
  SHA-256 checksum to a release.
- Users install from the Releases page, or better, point **[Obtainium](https://github.com/ImranR98/Obtainium)**
  at the repo URL for automatic updates.
- If you later want to publish the transliteration and heuristics code as a library (for an iOS app or
  other projects), *that* part could become a Kotlin Multiplatform package. It isn't worth doing until
  there is a second consumer.

One housekeeping item: the repo is **public** and keeps the 60 MB Farsi voice in **Git LFS**. The free
LFS allowance is 1 GB of bandwidth a month, about 16 clones. Consider attaching voices to a
release instead and removing them from LFS. CI already skips LFS downloads.

### Should the `babeltrout*` folders move under `sweetbriarcomputing.com/apps`?

**Move the app if you like; never move the keys.**

- `babeltrout/` is its own git repo with its own remote, like `apps/ecobee-trends`, so moving it to
  `sweetbriarcomputing.com/apps/babeltrout` is purely organizational and harmless. `deploy-site.sh` only
  ships `site/`. Moving it does make one folder hold several unrelated repos, so keep them as separate
  git repos rather than merging histories.
- **`babeltrout-keys/` must not live inside a website project**, or anywhere that gets deployed, synced
  or zipped (`sweetbriar-update.zip` is exactly the kind of archive that could sweep it up). Store the
  keystore and passwords in a password manager plus an encrypted backup, and point `keystore.properties`
  at it with an absolute path.
- **There are two different `release-keystore.jks` files.** `babeltrout/release-keystore.jks` and
  `babeltrout-keys/release-keystore.jks` differ. Only the one that signed the copy on your phone can
  sign updates. Find out which before building the next release (a release built with the wrong key
  fails to install over the existing app), then delete or clearly label the other.
- Loose build products (`beta_with_strip_ssml.apk`, which is actually a SherpaTTS 2.9 build, and
  `app/debug/app-debug.apk`) are git-ignored but clutter the folder. Move them to an archive folder.

### What tests should exist?

Added (JVM, run in seconds): language registry and code normalization, script and language
heuristics, conversation-pair resolution including turn alternation, all transliteration directions
(Persian lexicon and rules, Arabic, Cyrillic, Devanagari including nukta and schwa deletion), TTS
chunking and timeouts. 38 tests.

Recommended next:

| Layer | What | Tool |
|---|---|---|
| Unit | Translation route selection, entry formatting and export text | JUnit, after moving that logic out of the Activity |
| Unit | A "golden" file of 200 real Farsi sentences with hand-checked transliterations, to measure and improve the lexicon and rules | JUnit and a CSV fixture |
| Instrumented | ML Kit round-trip for each language pair (on a device or emulator with models) | AndroidX Test |
| Instrumented | TTS routing: pinned voice honoured, fallback when the engine is uninstalled | AndroidX Test |
| UI | Hold-button state machine: press, release, error, and permission denial in the middle of a hold | Espresso or Compose UI test |
| Manual | The on-phone checklist in [Test plan](#test-plan) | — |

### What has changed in the translation space since this was written?

- **ML Kit on-device translation has been frozen.** `translate` 17.0.3 and `language-id` 17.0.6
  (August 2024) are still the latest. Google's investment moved to **Gemini Nano** through the ML Kit
  **GenAI Prompt API**, which can translate on supported flagship phones (recent Pixels and some Samsung
  models), with much better handling of idiom and context. It's worth an optional "enhanced
  translation" path where available, keeping ML Kit as the universal fallback.
- **Open translation models got small enough for phones.** Google's **TranslateGemma** (January 2026,
  built on Gemma 3; the 4B size targets phones) covers 55 languages. Check whether Persian is among them
  before planning on it. It's heavier than ML Kit, but you control quality and the model.
- **Persian speech recognition is far better served.** A benchmark survey in August 2026 counted 82
  Persian-capable ASR models on Hugging Face, including Whisper fine-tunes around 25% WER on FLEURS
  Farsi and 2026 work on *spontaneous* (conversational) Persian. On-device Whisper and Moonshine-style
  models via **sherpa-onnx** are practical on current phones.
- **Persian TTS**: Mana-Persian-Piper (2025) and research on ezafe-aware phonemization directly target
  Persian's missing-vowel problem. See [FARSI_VOICES.md](FARSI_VOICES.md).
- **Android 13+ segmented recognition sessions and app-supplied audio** (`EXTRA_AUDIO_SOURCE`) make
  long-running dictation possible without restart beeps. This pass uses the first; the second is on the roadmap.
- **Android developer verification.** From **Sept 30, 2026** (Brazil, Indonesia, Singapore, Thailand)
  and **globally in 2027**, certified Android phones only install apps from verified developers, or
  through a new "advanced flow" with a 24-hour wait. **ADB installs stay exempt**, and a free
  hobbyist account covers up to 20 devices without government ID. Register for that before 2027 if
  anyone besides you will install Babeltrout. SherpaTTS's developer has said that app may stop working
  under these rules, which strengthens the case for bundling TTS inside Babeltrout.
- **Apple**: iOS 26 added Live Translation, but Apple's Translation framework and new SpeechAnalyzer
  still don't support Persian. That shapes the iPhone plan below.

### Can we add Hindi?

**Done.** ML Kit, Google speech recognition (`hi-IN`) and Google TTS all support Hindi, so it works
fully offline once models download. The transliteration line renders Hindi in Latin letters, and in
Cyrillic, Arabic or Persian script for those speakers. Google TTS usually needs its Hindi voice data
downloaded once: *Settings → Text-to-speech → Google → Install voice data*.

<a id="conversation-mode"></a>
### Conversation mode never worked well with longer speech. Can it be improved?

**Root cause:** Android's `SpeechRecognizer` is built for short voice commands. Each session ends at the
first pause, and the app has to restart it, which beeps, loses words between sessions, and splits one
thought into fragments translated out of context. The old code also cut spoken replies at 15 s and
discarded audio whenever a session ended in an error.

**Done now:**
1. **Long-speech mode** asks the recognizer (Android 13+) for a *segmented session*: one session spans
   pauses and delivers text in segments, and ends only after 2.5 s of true silence. The whole turn is
   then translated at once.
2. Partial results are kept, so errors and timeouts no longer throw away speech.
3. Spoken replies are no longer truncated.

Whether step 1 takes effect depends on the phone's recognizer; Android documents the extra as optional
for implementations. If the recognizer ignores it, the app falls back to the old behavior plus fixes 2
and 3. The test plan below checks this.

**The durable fix (recommended next project):** let the app own the microphone.

```
AudioRecord (16 kHz) ──► Silero VAD (speech/silence) ──► utterance buffer ──► on-device ASR ──► translate ──► TTS
        ▲                                                                       (Whisper / sherpa-onnx)
        └────────── muted while TTS is speaking (no self-translation) ───────────────────────────────────┘
```

- No sessions, no beeps, no length limit. Turn-taking comes from the voice-activity detector.
- One library, **sherpa-onnx**, provides VAD, Whisper-family ASR (with Persian fine-tunes), *and* Piper
  TTS, so it also solves the SherpaTTS dependency (see Farsi below).
- A lighter middle step: keep Google's recognizer but feed it the app's own audio through
  `EXTRA_AUDIO_SOURCE` plus a segmented session. The session then lasts until the app closes the stream.

### How can installation be easier?

In order of payoff:
1. **Bundle Farsi TTS inside the app** (sherpa-onnx library and a downloadable Piper voice). This removes
   the whole "Install TTS APK → open SherpaTTS → import voice files → select voice" sequence, which is by
   far the hardest part of setup today, and survives SherpaTTS being blocked in 2027.
2. **GitHub Releases + Obtainium** for one-tap installs and updates (workflow added).
3. **First-run wizard** instead of a diagnostics page: microphone permission → download models
   (showing size, Wi-Fi only) → Farsi voice → spoken test. The pieces already exist in the Setup page.
4. **Register as an Android hobbyist developer** (free, up to 20 devices) before the 2027 global rollout.
5. Google Play is possible later, but requires dropping `REQUEST_INSTALL_PACKAGES`, a privacy policy,
   and 12 testers for 14 days on new personal accounts.

<a id="iphone-version"></a>
### Can there be an iPhone version?

**Yes, and Farsi is easier to support on iPhone than you might expect, because Apple's own services
are skipped for it.**

| Piece | Android today | iPhone |
|---|---|---|
| Translation | ML Kit | **ML Kit for iOS** (same models, Persian supported). Apple's Translation framework lacks Persian. |
| Speech → text | Google recognizer | Apple Speech for English, Spanish, French, Russian, Ukrainian, Arabic and Hindi; **WhisperKit or sherpa-onnx (Whisper)** for Persian, since Apple has no Persian recognizer |
| Text → speech | SherpaTTS / Google | `AVSpeechSynthesizer` for most languages; **sherpa-onnx with Piper voices** for Persian, bundled, no separate app |
| Transliteration and heuristics | Kotlin | **Shared via Kotlin Multiplatform**: the extracted pure-Kotlin files compile for iOS unchanged |

Recommended approach: a **native SwiftUI app** plus a small **KMP shared module** for `Languages`,
`ScriptHeuristics` and `TransliterationEngine`. It needs a Mac with Xcode (you have one) and an Apple
Developer membership ($99/yr) to install beyond 7-day free provisioning and for TestFlight. Build the
sherpa-onnx pipeline on Android first; the same models and code paths then carry over to iOS.

<a id="farsi"></a>
### Improving the Farsi experience, and choosing among Farsi voices

**Voices:** there are **six** good offline Farsi Piper voices: amir, gyro, ganji, ganji_adabi,
reza_ibrahim, and the newer Mana. You already have the first five downloaded. The new **Choose
Voices** button lists every Farsi voice any installed engine exposes, plays a sample, and remembers
your pick. [FARSI_VOICES.md](FARSI_VOICES.md) covers where each comes from, licenses, how to install
several, and tuning speed through `length_scale`.

**Next Farsi improvements, by value:**
1. **In-app Piper engine via sherpa-onnx**, with a voice catalog downloaded on demand. All six voices can
   be switched instantly in-app. That removes the SherpaTTS limitation of one active model per
   language, and survives the 2027 verification change.
2. **Ezafe-aware phonemization** in front of Piper, as the Mana authors recommend. It fixes the
   commonest mispronunciations (missing *-e* linkers and short vowels).
3. **Better transliteration from the same G2P**: once the app has a Persian phonemizer, drive the
   pronunciation line from it instead of rules and lexicon. That gives true vowels
   ("ketaab-e man", not "ktaab mn").
4. **Grow the lexicon** meanwhile (it's a plain map in `TransliterationEngine`); the golden-file test
   above would measure progress.
5. **Persian ASR**: Google's `fa-IR` recognition is fair on clean speech and weak on colloquial speech.
   An on-device Whisper Persian fine-tune would be better and fully offline.
6. **Colloquial vs. formal register**: ML Kit produces formal written Persian (*mikhaaham*), while
   people say *mikhaam*. An optional "spoken style" post-processor, or an LLM path, would sound far more
   natural when read aloud.
7. **Dari and Tajik**: `normalizeCode` already maps `prs` (Dari) to Farsi. Offering Dari-specific
   voices later would serve Afghan speakers.

---

## Other recommendations

<a id="privacy"></a>
- **Privacy**: say plainly in the app that speech recognition may be processed by Google. Offer a
  "prefer offline recognition" switch (`preferOfflineRecognition` is already a constant, currently
  `false`). The sherpa-onnx pipeline would make the whole app offline.
- **Architecture**: split `MainActivity` (still about 2,600 lines) into a `ViewModel` with a small state
  machine per mode, plus `TtsRouter`, `TranslationService` and `SpeechCapture` classes. That makes the
  Android-side behavior testable and rotation-safe. Today a screen rotation restarts the Activity and
  clears the transcript; locking orientation or saving state are the quick fixes.
- **UI**: move to Jetpack Compose when refactoring. Also add a large "show to the other person"
  full-screen view of the last translation, a swap-direction button in conversation mode, and a
  RecyclerView for long sessions.
- **Phrasebook**: save favorite translations (with audio) offline, useful at a clinic or front desk.
- **Accessibility**: content descriptions on hold buttons, TalkBack announcements for results, and
  haptic feedback on press and release.
- **Localization of the UI itself**: strings are hard-coded in English; moving them to `strings.xml`
  enables a Farsi and Ukrainian UI.
- **Dependency hygiene**: enable Dependabot or Renovate for Gradle and Actions updates.
- **Crash visibility**: keep line numbers (done in `proguard-rules.pro`), and consider an opt-in local
  crash log users can export with the transcript.

## Roadmap

| Phase | Scope |
|---|---|
| **1.2 (this pass)** | Hindi, voice chooser, long-speech mode, salvage and timeout fixes, Farsi transliteration, R8 and size, tests, CI and releases, docs |
| **1.3** | Verify 1.2 on the phone; resolve the keystore question; first-run wizard; privacy switch; register the hobbyist developer account |
| **2.0** | sherpa-onnx inside the app: Piper TTS with an in-app Farsi voice catalog, Silero VAD, owned-mic conversation mode; SherpaTTS becomes optional |
| **2.1** | On-device Persian ASR (Whisper fine-tune); Persian G2P for pronunciation and transliteration |
| **3.0** | iPhone app (SwiftUI + KMP shared core + ML Kit iOS + sherpa-onnx) |

## Test plan

Before tagging 1.2, on your phone:

- [ ] Fresh install → Install Assets downloads 7 models with progress text.
- [ ] Push-to-talk in each language, including **Hindi**; pronunciation lines appear for Ukrainian, Russian and Hindi targets.
- [ ] Settings survive an app restart (target, rate, text size, conversation pair).
- [ ] **Choose Voices → Farsi** lists your SherpaTTS voices; choosing one plays a sample; Farsi output then uses it; **Automatic** resets.
- [ ] **Diagnose Farsi TTS** lists voices and the chosen one.
- [ ] Conversation mode, English ↔ Farsi, Long-speech **on**: speak three sentences with short pauses. You should get one translation of the whole turn, and a long reply should be spoken to the end.
- [ ] Same with Long-speech **off**: behaves as before, but speech is no longer lost when the recognizer errors.
- [ ] Release build (`scripts/build_release_dist.sh`) installs over the existing app, which confirms the right keystore, and runs normally, which confirms R8 didn't strip anything needed.
