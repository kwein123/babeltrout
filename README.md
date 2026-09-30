# Babeltrout

An Android speech-to-speech translator built for real conversations. It starts with **English, Farsi,
Ukrainian, Russian, Arabic, Hindi, French, Spanish and German**, and you can remove those or add 16 more
(Italian, Portuguese, Dutch, Polish, Turkish and others). Hold a button and speak; Babeltrout transcribes, translates
on-device, speaks the result aloud and shows a pronunciation line written in *your* alphabet, so you
can say the reply yourself.

## How Babeltrout differs

Compared with Google Translate, Microsoft Translator, DeepL, and the translators built into Pixel and Samsung
phones (as of September 2026), Babeltrout offers:

- **Pronunciation in *your* alphabet.** Every translation into a non-Latin script comes with a line showing how
  to say it, written in the reader's own script: Latin, Cyrillic, Persian/Arabic or Devanagari. Other apps show
  Latin-letter romanization at most. Real output for one Farsi sentence:

  | | |
  |---|---|
  | Translation | سلام، حال شما چطور است؟ |
  | For an English speaker | salaam, haal shomaa chetor ast? |
  | For a Ukrainian speaker | салам, гал шома четор аст? |

- **Offline Farsi voices.** Five natural-sounding Farsi voices (and three Hindi ones) download inside the app
  and run without internet. Google's Android speech engine has no Farsi voice at all. You can pick the voice
  for each language from every speech engine on the phone.
- **Hands-free conversation that tells look-alike languages apart.** No buttons, beeps or time limit. Each turn
  is recognized in both languages at once, and Babeltrout picks the real one, separating Farsi from Arabic and
  Ukrainian from Russian, even for one-word replies.
- **Private by design.** Translation always runs on the phone. No account, no ads, no analytics of its own;
  history stays in memory and out of backups. You choose your languages (25 available), and updates come
  straight from GitHub.

**Where the big apps are ahead:** far more languages (100+), more fluent cloud translation, camera and typed
input, and iPhone versions.

## Features

- **Push-to-talk**: hold the button for the language you're speaking.
- **Your languages**: tap **✎ Edit** above the buttons to remove a language (and its downloads) or add one.
- **Conversation mode**: two people, two languages, one phone. Each phrase is routed to the other language.
- **Offline translation** with Google ML Kit (models download once, about 30 MB per language).
- **Pronunciation hints**: Farsi, Arabic, Hindi, Ukrainian and Russian output gets a transliteration line
  written in the speaker's script (Latin for English, Cyrillic for Ukrainian, and so on).
- **Built-in Farsi voices**: download any of five offline Piper Farsi voices (and three Hindi ones) inside
  the app, and switch between them. No separate voice app needed.
- **Transcript export** to `.txt`. History lives only in memory and is excluded from Android backups.

> **Privacy note:** translation always runs on the phone. *Speech recognition* uses Android's system
> recognizer (normally Google's), which may send audio to Google unless the device has an offline
> speech pack for that language. See [docs/REVIEW-2026.md](docs/REVIEW-2026.md#privacy).

## Documentation

| Doc | What's in it |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the app is put together, the speech → translate → speak pipeline, and where to change things |
| [docs/FARSI_VOICES.md](docs/FARSI_VOICES.md) | Every Farsi voice worth trying, how to install several, and how to pick one |
| [docs/REVIEW-2026.md](docs/REVIEW-2026.md) | 2026 review: what changed, answers to open questions, roadmap (iPhone, packaging, conversation mode) |
| [docs/PUNCHLIST.md](docs/PUNCHLIST.md) | Remaining work, with what needs your hands |
| [CHANGELOG.md](CHANGELOG.md) | Release history |

## Install on a phone

**Easiest (from a release):** download `babeltrout-vX.Y-arm64-v8a.apk` (most phones) from the repository's
[Releases](https://github.com/kwein123/babeltrout/releases) page on the phone, open it and allow the
install. Or use [Obtainium](https://github.com/ImranR98/Obtainium) with this repo's URL to get automatic
updates.

**From your Mac with a USB cable** (always works, including after Google's 2026–27 sideloading changes):

```bash
./scripts/build_install_debug.sh
```

That builds a debug APK and installs it with `adb`. The phone needs *Developer options → USB debugging*
enabled (Settings → About phone → tap *Build number* 7 times).

### First launch

1. Grant microphone permission.
2. With internet on, open **Setup & diagnostics → Install Assets** once. This downloads the translation
   models; after that, translation works offline.
3. For Farsi speech output: **Setup → Voice Library → Farsi**, pick a voice (about 21 MB compact).
   See [docs/FARSI_VOICES.md](docs/FARSI_VOICES.md) for the choices.
4. Optional: **Choose Voices** to pick a specific voice per language. It plays a sample when you choose.

## Using it

1. Pick a **Target language**.
2. Hold a talk button while speaking and release when done.
3. Each entry shows the translation, a pronunciation line and your original words. Tap **Speak** to replay it.

**Conversation mode** (*Conversation* button): choose Language A and B, then turn on the mic. On Android 13+
**Hands-free mic** (default) keeps listening with no beeps and no time limit: just talk, and each pause of
about a second ends a turn, which is translated and spoken in the other language. It works out which of
the two languages each person used, even for single words.

## Build from source

Requirements: Android Studio (it bundles the JDK and Android SDK). Open this folder in Android Studio
and let Gradle sync, or use the command line:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest   # unit tests (seconds, no phone needed)
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

### Signed release builds

Release APKs must always be signed with the **same key**, or phones refuse the update. Keep the keystore
and its passwords **outside** this folder, in a password manager plus an encrypted backup.

1. Copy `keystore.properties.example` to `keystore.properties` and point `storeFile` at the keystore
   (an absolute path is fine). Both files are git-ignored.
2. Run `./scripts/build_release_dist.sh`. Output goes to `dist/`.

To have GitHub build releases, add the four signing secrets described at the top of
[.github/workflows/release.yml](.github/workflows/release.yml), then push a tag such as `v1.2`.
Every push also runs the unit tests via [android-ci.yml](.github/workflows/android-ci.yml).

## Troubleshooting

| Symptom | Fix |
|---|---|
| Nothing is recognized | Check microphone permission, and that you're holding the button for the language you're speaking. |
| "language unavailable" | Install that language's offline speech pack in Android Settings → Google → Voice, or stay online. |
| Translation fails for a language | Setup → **Install Assets** again with internet on. |
| Farsi is silent | Setup → **Diagnose Farsi TTS** and follow its fix list; see [docs/FARSI_VOICES.md](docs/FARSI_VOICES.md). |
| Conversation mode cuts off or beeps | Make sure **Hands-free mic** is on (Android 13+). If it turned itself off, the phone's recognizer rejected app audio; see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#conversation-mode-in-detail). |

## Credits and licenses

- Translation and language ID: [Google ML Kit](https://developers.google.com/ml-kit) (on-device).
  ML Kit's networking libraries, [OkHttp](https://github.com/square/okhttp) and
  [Okio](https://github.com/square/okio) by Square (Apache-2.0), are pinned to patched versions in
  `app/build.gradle.kts`.
- Built-in voices: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) by k2-fsa, running
  [Piper](https://github.com/rhasspy/piper) voices from
  [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices) as packaged by sherpa-onnx; each voice's
  license is shown in the Voice Library and in its `MODEL_CARD`. Archives are unpacked with
  [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) (Apache-2.0).
- Hands-free conversation: [Silero VAD](https://github.com/snakers4/silero-vad) by Silero Team (MIT),
  bundled as `app/src/main/assets/silero_vad.onnx` from sherpa-onnx's `asr-models` release, run through sherpa-onnx.
- Optional Farsi speech via [SherpaTTS](https://github.com/woheller69/ttsEngine) by woheller69, built on
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (k2-fsa) and [Piper](https://github.com/rhasspy/piper).
  Babeltrout talks to it through Android's standard TTS interface and does not include its code.
- `fa_IR-amir-medium.onnx` (bundled for convenience): Piper voice from
  [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices/tree/main/fa/fa_IR/amir/medium), trained
  on the [Datacula](https://datacula.com/tts-databases) Persian dataset, **CC0**.

Icon options live in `app/src/main/res/drawable/ic_babeltrout*.xml`; switch by editing `android:icon` and
`android:roundIcon` in `AndroidManifest.xml`, or preview them in-app under Setup → Icon Preview.
