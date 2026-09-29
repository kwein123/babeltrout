# Farsi (Persian) voices

Google TTS, the default voice engine on most Android phones, doesn't ship a Persian voice. Babeltrout
therefore speaks Farsi through **SherpaTTS**, a free, offline Android TTS engine that runs **Piper**
neural voices. Any Piper voice is a pair of files: `name.onnx` (the model, about 60 MB) and
`name.onnx.json` (its config).

## Voices worth trying (September 2026)

All are "medium" Piper models (22 kHz, about 60 MB each) and all run offline.

| Voice | Files | Source | License | Notes |
|---|---|---|---|---|
| **amir** | `fa_IR-amir-medium` | [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices/tree/main/fa/fa_IR/amir/medium) | CC0 | The voice bundled in this repo. Clear, neutral. |
| **gyro** | `fa_IR-gyro-medium` | [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices/tree/main/fa/fa_IR/gyro/medium), by [gyroing](https://github.com/gyroing) | see source | Fine-tuned from a male Persian VITS dataset. Works well without an external normalizer. |
| **ganji** | `fa_IR-ganji-medium` | [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices/tree/main/fa/fa_IR/ganji/medium) | CC0 | [Datacula](https://tts.datacula.com/) dataset. |
| **ganji_adabi** | `fa_IR-ganji_adabi-medium` | [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices/tree/main/fa/fa_IR/ganji_adabi/medium) | CC0 | Same dataset family, literary (*adabi*) reading style. More formal. |
| **reza_ibrahim** | `fa_IR-reza_ibrahim-medium` | [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices/tree/main/fa/fa_IR/reza_ibrahim/medium) | CC0 | Trained on Quran-reading audio (Persian plus English). Can sound recitational; also handles English words. |
| **mana** | `fa_IR-mana-medium` | [MahtaFetrat/Mana-Persian-Piper](https://huggingface.co/MahtaFetrat/Mana-Persian-Piper) | MIT | Newest (2025). Fine-tuned from amir on the [Mana-TTS](https://huggingface.co/datasets/MahtaFetrat/Mana-TTS) dataset. Its authors report the best pronunciation when paired with ezafe-aware phonemization ([arXiv 2512.08006](https://arxiv.org/abs/2512.08006)). |

The first five are also packaged for sherpa-onnx as `vits-piper-fa_IR-<name>-medium`
([sherpa-onnx TTS model list](https://k2-fsa.github.io/sherpa/onnx/tts/all/Persian/index.html)).

You can't judge voices from a table. Listen to all of them with the same sentence (see
*Comparing voices* below) and keep the one or two you like.

### Other options (not offline Piper)

* **Microsoft Edge neural voices** (`fa-IR-DilaraNeural`, `fa-IR-FaridNeural`): noticeably more natural,
  but online only, through an unofficial endpoint. A candidate for an optional "online voice" mode, not a default.
* **Meta MMS-TTS (Persian)**: covers 1,100+ languages; the license is non-commercial and the quality is below Piper's.

## Installing more than one voice

1. Install SherpaTTS: [F-Droid](https://f-droid.org/packages/org.woheller69.ttsengine/) (preferred;
   updates itself) or its [GitHub releases](https://github.com/woheller69/ttsEngine/releases).
   Babeltrout's **Install TTS APK** button also works if you already have the APK on the phone.
2. Open SherpaTTS once so it finishes its first-run setup.
3. For each voice: copy its `.onnx` and `.onnx.json` to the phone, then in Babeltrout tap
   **Setup → Import Voice Files**, pick both files, and finish the import inside SherpaTTS.
   (SherpaTTS's own built-in downloader can also fetch Piper voices directly from Hugging Face.)
4. In Babeltrout, tap **Setup → Choose Voices → Farsi**. You'll see every Farsi voice that *any*
   installed engine exposes. Pick one and it plays a sample. Your choice is remembered, and **Automatic**
   returns to the default routing.
5. Run **Diagnose Farsi TTS**. The report now lists every Farsi voice found and which one is chosen.

**If only one Farsi voice appears** even though you imported several, SherpaTTS is exposing only its
currently active model for Persian. Switch models inside the SherpaTTS app; Babeltrout then uses
whichever is active. This limitation is the main reason for the *in-app voice engine* on the roadmap.

## Comparing voices

In **Choose Voices → Farsi**, tap each voice in turn; each tap plays the same test sentence. For a
real-world comparison, set the target language to Farsi, say a sentence in English, and use **Speak**
on the entry after switching voices.

On a Mac you can compare every model on identical text in a minute with Piper's CLI:

```bash
pip install piper-tts
for v in amir gyro ganji ganji_adabi reza_ibrahim; do
  echo "سلام، امروز حال شما چطور است؟ من دارم فارسی یاد می‌گیرم." |
    piper -m fa_IR-$v-medium.onnx -f sample-$v.wav
done
open sample-*.wav
```

## Tuning a voice

Each `.onnx.json` has an `inference` block:

```json
"inference": { "noise_scale": 0.667, "length_scale": 1, "noise_w": 0.8 }
```

* `length_scale` controls speed. `1.15` is about 15% slower, which helps learners. Babeltrout's
  speech-rate slider also works on top of this.
* `noise_scale` and `noise_w` control expressiveness and variation. Lower is flatter and more robotic;
  higher is livelier but can wobble.

Edit the JSON before importing. Keep the original, and rename the copy (for example
`fa_IR-amir_slow-medium.onnx.json`, with the `.onnx` file renamed to match) so both can coexist.

## Why Farsi pronunciation is sometimes off

Written Persian omits most short vowels and the *ezafe* linker (the "-e" in *ketaab-e man*, "my book").
Piper voices rely on eSpeak-ng to guess them, and it guesses wrong on some words. This is also why
Babeltrout's Farsi transliteration line uses a built-in lexicon of common words plus vowel rules: the
letters alone don't say how a word sounds. The Mana voice's authors address this with a dedicated
Persian G2P (grapheme-to-phoneme) front end. Integrating one is on the roadmap
([REVIEW-2026.md](REVIEW-2026.md#farsi)).

## Heads-up: SherpaTTS and Android developer verification

SherpaTTS's developer has said the app may stop working on Google-certified Android devices once
Google's developer-verification rules take effect. Those rules start in four countries on September 30,
2026, and apply globally in 2027. If that happens, the fix is to move Farsi synthesis *inside*
Babeltrout (sherpa-onnx as a library, same Piper voices), which also removes the separate install
entirely. See the roadmap.
