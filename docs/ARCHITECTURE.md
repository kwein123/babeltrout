# Babeltrout architecture

A single-Activity Android app (Kotlin, View Binding, coroutines). There's no server. Every
capability comes from the phone's own services plus Google ML Kit.

## The pipeline

```
 mic ──► SpeechRecognizer ──► transcript(s) ──► source language ──► ML Kit Translate ──► target text
          (Android system,     up to 5            decision                (on-device)          │
           usually Google)     alternatives                                                   │
                                                                                               ▼
     screen ◄── entry view (target, pronunciation, source) ◄── TransliterationEngine ◄────────┤
                                                                                               ▼
                                                     speaker ◄── TextToSpeech (engine routed per language)
```

1. **Capture.** `SpeechRecognizer` with a `RecognizerIntent`. Push-to-talk starts on touch-down and
   calls `stopListening()` on release. Conversation mode uses a second recognizer that restarts itself.
2. **Pick the transcript and language.** The recognizer returns up to five alternatives.
   `ScriptHeuristics` scores them by script (Arabic, Cyrillic, Devanagari or Latin) and marker
   letters and words. ML Kit Language ID confirms. In conversation mode, `PairSourceResolver`
   decides which of the two languages was spoken, and on a tie assumes the speakers alternate.
3. **Translate.** `Translation.getClient(src, tgt)`. ML Kit models are all *X ↔ English*, so
   non-English pairs pivot through English inside ML Kit. The app makes one call per phrase.
4. **Transliterate.** `TransliterationEngine.toLatin(target)` produces a Latin reading, and
   `latinToScript(latin, sourceLanguage)` rewrites it in the speaker's alphabet.
5. **Speak.** `resolveRouteForOutputCode` picks an engine and voice in this order: the voice the user
   pinned in *Choose Voices*, then SherpaTTS for Farsi or Google TTS for everything else, then the
   system default. Long text is chunked to the engine's input limit (`SpeechText`).

## Source files

| File | Responsibility | Tested |
|---|---|---|
| `Languages.kt` | The language list (code, label, locale, script, sample phrase) and code normalization (`fa-IR`, `fas` → `fa`). **Add a language here first.** | ✅ |
| `ScriptHeuristics.kt` | Script detection, Farsi/Arabic and Ukrainian/Russian disambiguation, transcript scoring, `PairSourceResolver` | ✅ |
| `TransliterationEngine.kt` | Arabic, Persian, Cyrillic and Devanagari to Latin; Latin to each script. Persian uses a lexicon plus positional vowel rules | ✅ |
| `SpeechText.kt` | TTS chunking and speaking timeouts | ✅ |
| `TurnDetector.kt` | VAD flags → conversation turns (pre-roll, length cap) | ✅ |
| `TurnLanguageChooser.kt` | Picks the language of a turn recognized in both languages | ✅ |
| `VoiceActivityMic.kt`, `RecognizerAudioPipe.kt`, `DualTurnRecognizer.kt` | App-owned mic, audio pipes, parallel per-language recognition | manual (device-tested) |
| `PiperVoiceCatalog.kt` | Downloadable built-in voices: URLs, sizes, pinned SHA-256 digests | ✅ |
| `PiperVoiceStore.kt` | Download, verify, safely unpack, list and delete built-in voices | ✅ (extraction and discovery) |
| `PiperSpeaker.kt` | Runs a Piper voice with sherpa-onnx on one worker thread, streaming audio to an `AudioTrack` | manual |
| `MainActivity.kt` | Everything Android: UI pages, recognizers, TTS engines, ML Kit, diagnostics, export | manual |

The pure-Kotlin files have no Android imports, so they run as plain JVM unit tests
(`./gradlew :app:testDebugUnitTest`), and they could be shared with an iOS build through Kotlin
Multiplatform (see [REVIEW-2026.md](REVIEW-2026.md#iphone-version)).

## UI

One layout (`activity_main.xml`) holds four `ScrollView` "pages" whose visibility is toggled:
`MAIN` (push-to-talk), `SUPPORT` (setup and diagnostics), `CONVERSE`, and `ICON_PREVIEW`. Back returns to MAIN.
Output entries are inflated from `item_entry.xml` and `item_converse_entry.xml` into plain
`LinearLayout`s. That works for dozens of entries; switch to `RecyclerView` if sessions get long.

## Conversation mode, in detail

### Hands-free (default on Android 13+)

```
AudioRecord 16 kHz ─► Silero VAD (sherpa-onnx) ─► TurnDetector ─┬─► pipe ─► SpeechRecognizer locked to language A ─┐
 (VoiceActivityMic)     speech / silence flags    pre-roll,     │                                                    ├─► TurnLanguageChooser ─► translate ─► speak
                                                  90 s cap      └─► pipe ─► SpeechRecognizer locked to language B ─┘       (mic muted until done)
```

* `VoiceActivityMic` records continuously and runs Silero VAD on each 32 ms frame. `TurnDetector` (pure,
  tested) prepends 0.6 s of pre-roll so first syllables aren't clipped, and ends a turn after about 1.2 s
  of silence or 90 s of speech.
* `DualTurnRecognizer` streams each turn through `RecognizerAudioPipe`s into two `SpeechRecognizer`s, one
  per conversation language (`EXTRA_AUDIO_SOURCE` + `EXTRA_SEGMENTED_SESSION`). Each is locked to its
  language: auto-detection needs seconds of speech and stuck to the first language. On a Pixel 9 Pro XL
  both run in parallel; if a service refuses, the lane is replayed sequentially from buffered audio.
* `TurnLanguageChooser` (pure, tested) picks the candidate: wrong-script answers lose, then recognizer
  confidence (ignored if it proves to be a fixed placeholder, as Google's Ukrainian model's 0.9397 is),
  then ML Kit text language ID, then marker words, then turn-taking. The log (tag `Babeltrout`) records
  each decision (codes and scores only, never the words).
* If the recognizer rejects app-supplied audio, the app switches to the classic mode below and turns the
  Hands-free switch off.

### Classic (Android 12 and earlier, or Hands-free off)

Android's `SpeechRecognizer` is session-based: one session, one utterance, and it closes the
microphone when it hears a pause. The app restarts it after every result, which causes the beeps and
the lost words between sessions.

* **Android 13+ (Long-speech mode, default on):** the intent sets
  `EXTRA_SEGMENTED_SESSION = EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` with 2.5 s. Recognizers
  that support it keep one session open across short pauses, delivering `onSegmentResults` chunks, and
  end with `onEndOfSegmentedSession` after a real 2.5 s silence. The app buffers segments and translates
  the whole turn.
* **All versions:** partial results are on, so if a session ends in an error or timeout, the words
  already heard are salvaged and translated instead of dropped.
* **Speaking:** the mic is paused while TTS talks (no self-translation), with a timeout that scales
  with text length. The old fixed 15 s timeout cut off long replies.

Recognizers that ignore the segmented extra fall back to normal `onResults`, which is still handled.
The planned permanent fix (the app owns the mic, with its own voice-activity detection and
recognizer) is in [REVIEW-2026.md](REVIEW-2026.md#conversation-mode).

## TTS engine routing

Built-in Piper voices (`PiperSpeaker`) bypass Android's TTS system entirely: Babeltrout generates the audio
with sherpa-onnx and plays it through `AudioTrack`. They are chosen first when pinned, and automatically for Farsi.

| Output language | Preferred engine | Fallback |
|---|---|---|
| any language with a pinned voice | the pinned voice (built-in or system engine) | automatic routing below |
| Farsi, nothing pinned | built-in Piper voice, if one is installed | SherpaTTS, then system default |
| Farsi | SherpaTTS (`org.woheller69.ttsengine*`) | system default engine |
| everything else | Google TTS (`com.google.android.tts`) | system default engine |

`TextToSpeech` instances are cached per engine package (`namedEngineTts`). When the user changes the
system default engine, `onResume` notices and rebuilds them.

## Persistence

`SharedPreferences("babeltrout_prefs")`: target language, conversation languages, speech rate, text
size, long-speech toggle, idle auto-restart, first-run asset flag, and pinned voices
(`voice_<code>` = `enginePackage|voiceName`). Transcripts are never written to disk except by the
user's explicit export. Backup and device transfer are disabled (`data_extraction_rules.xml`).

## Adding a language

1. Add a `LanguageOption` in `Languages.kt`. Check that ML Kit supports it
   (`TranslateLanguage.fromLanguageTag`) and that Google speech recognition supports the locale.
2. Add a hold button in `activity_main.xml` and bind it in `setupHoldButtons()`.
3. For a new script, add its range to `ScriptHeuristics` and to/from-Latin rules in `TransliterationEngine`.
4. Add a test for each of the above.

## Build configuration

* AGP 9.4.1, Gradle 9.8.0, Kotlin 2.4.20, JDK 21 toolchain, compile and target SDK 36, min SDK 26.
* Release builds: R8 minify and resource shrinking; one APK per CPU type (`arm64-v8a` about 47 MB,
  `armeabi-v7a` about 33 MB). Signing comes from `keystore.properties`, which is git-ignored.
* sherpa-onnx comes from JitPack (restricted to its group in `settings.gradle.kts`); its AAR has no
  R8 rules, so `proguard-rules.pro` keeps `com.k2fsa.sherpa.onnx.**` for its JNI field lookups.
* CI: `.github/workflows/android-ci.yml` (tests and a debug APK on each push) and
  `release.yml` (a signed APK on a `v*` tag).
