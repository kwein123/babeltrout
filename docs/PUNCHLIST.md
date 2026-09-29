# Babeltrout punch list
**Started:** Mon 28 Sep 2026 · **Baseline:** v1.2 on branch `modernize-2026`

Everything suggested in [REVIEW-2026.md](REVIEW-2026.md) that isn't done yet, in the order I'd do it.
The **You** column is what needs your hands (accounts, phone, decisions); Claude can do the rest.

Tick items off as they land (`- [x]`).

| # | Item | Why | Effort | You |
|---|---|---|---|---|
| 1 | Resolve which keystore signs the app | Wrong key means updates won't install | 15 min | ✅ |
| 2 | Phone test of v1.2 | Nothing Android-side has run on a device yet | 30 min | ✅ |
| 3 | Merge to `main`, set up signing secrets, tag `v1.2` | First real GitHub Release | 20 min | ✅ |
| 4 | Secure the keys folder | Key lives in plain folders today | 20 min | ✅ |
| 5 | Register Android hobbyist developer account | Global sideload rules in 2027 | 30 min | ✅ |
| 6 | Tidy the project folder | Stray APKs, LFS model | 15 min | small |
| 7 | Privacy: "prefer offline recognition" switch + disclosure | Recognition may send audio to Google | 1 hr | decide |
| 8 | Keep transcript across screen rotation | Rotation currently wipes it | 1 hr | — |
| 9 | First-run setup wizard | Setup is scattered across a diagnostics page | 3–4 hr | test |
| 10 | "Show to the other person" full-screen view | Easier face-to-face use | 1–2 hr | — |
| 11 | Swap button + big transcript list in conversation mode | Usability, long sessions | 1–2 hr | — |
| 12 | Farsi golden-file test (≈200 sentences) | Measure and improve Farsi pronunciation line | 2 hr + review | ✅ review |
| 13 | Grow the Farsi lexicon from #12 | Better pronunciation lines now | ongoing | ✅ review |
| 14 | Move UI strings to `strings.xml`; Farsi/Ukrainian UI | Localized app, accessibility | 3 hr | translate/check |
| 15 | Accessibility pass | TalkBack, haptics, content descriptions | 2 hr | test |
| 16 | Phrasebook (saved phrases with audio) | Clinic / front-desk use | 4 hr | — |
| 17 | Dependabot / Renovate | Keep Gradle and Actions current | 10 min | approve PRs |
| 18 | Split `MainActivity` into ViewModel + services | Testability, stability | 1–2 days | — |
| 19 | **v2.0: sherpa-onnx inside the app** | Built-in Farsi voices, no SherpaTTS, real conversation mode | 1–2 weeks | test |
| 20 | Persian G2P (ezafe-aware) | Correct Farsi vowels in speech and pronunciation line | 1 week | review |
| 21 | On-device Persian speech recognition (Whisper) | Better colloquial Farsi, fully offline | 1 week | test |
| 22 | Optional enhanced translation (Gemini Nano / TranslateGemma) | Idiom and context on capable phones | 3–5 days | decide |
| 23 | Colloquial "spoken Farsi" output option | ML Kit writes formal Persian | research | review |
| 24 | iPhone app | Second platform | 3–6 weeks | ✅ Apple account |
| 25 | Google Play listing (optional) | Widest reach | 1 day + 14-day test | ✅ |

---

## Now (before and right after the first release)

### 1. Resolve which keystore signs the app
`babeltrout/release-keystore.jks` and `babeltrout-keys/release-keystore.jks` are different files. Only the
one that signed the Babeltrout already on your phone can sign updates.

**You:**
- [ ] With the phone connected over USB, ask Claude to compare the installed app's signing certificate
      against both keystores (needs the keystore password; you type it, Claude never sees it).
      Or: `apksigner verify --print-certs` on the old APK vs `keytool -list -v -keystore <file>` for each.
- [ ] If *neither* matches (or the phone only ever had debug builds), pick one keystore as the permanent
      key; the first release install will need an uninstall/reinstall once.
- [ ] Delete or rename the other file (`OLD-do-not-use.jks`) so it can't be confused again.

### 2. Phone test of v1.2
**You:** run the checklist in [REVIEW-2026.md → Test plan](REVIEW-2026.md#test-plan). Report anything odd;
the most important checks are **Long-speech mode** (does one turn with pauses come out as one translation?)
and **Choose Voices → Farsi** (how many Farsi voices does SherpaTTS expose?).

### 3. Merge, secrets, first release
- [ ] Review and merge `modernize-2026` into `main` (Claude can open the PR).
- [ ] GitHub → repo **Settings → Secrets and variables → Actions**, add: `RELEASE_KEYSTORE_BASE64`
      (`base64 -i <the right .jks> | pbcopy`), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
- [ ] Tag: `git tag v1.2 && git push origin v1.2`. The Release workflow publishes the signed APK.
- [ ] On the phone, install **Obtainium** and add `https://github.com/kwein123/babeltrout` for auto-updates.

### 4. Secure the keys
- [ ] Store the keystore file and both passwords in your password manager (1Password/Bitwarden attachments work).
- [ ] Keep one encrypted backup (e.g. an encrypted disk image or the NAS with encryption).
- [ ] Move the keystore out of `~/projects/babeltrout/`; point `keystore.properties` → `storeFile=` at the new absolute path.
- [ ] Never place `babeltrout-keys/` under `sweetbriarcomputing.com/` or anything that gets zipped/deployed.

### 5. Android developer verification (2027)
- [ ] Create the free **hobbyist** developer account in the Android Developer Console (covers up to
      20 devices, no government ID) and register the app's package name `com.kevin.babeltrout` and signing key.
      Do this after #1 so the right key is registered.
- Until then, USB installs (`scripts/build_install_debug.sh`) always work.

### 6. Tidy the folder
Claude can do this once you say go:
- [ ] Move `beta_with_strip_ssml.apk` (a SherpaTTS 2.9 build) and `app/debug/app-debug.apk` to an archive folder.
- [ ] Decide on the LFS model: keep it (fine for now), or attach voices to a GitHub Release and remove from
      LFS to avoid the 1 GB/month free bandwidth cap. **You:** decide.
- [ ] Optionally move `babeltrout/` to `sweetbriarcomputing.com/apps/babeltrout` (repo moves intact).

## Next (v1.3 polish)

### 7. Privacy switch
Add a Setup toggle "Prefer offline speech recognition" (wires to the existing `preferOfflineRecognition`)
and a one-line notice on first run that recognition may use Google's servers.
**You:** decide default on or off; install offline speech packs for your languages
(Settings → Google → Voice / "Offline speech recognition") to see which work offline.

### 8. Rotation
Save the transcript in `onSaveInstanceState` (or a ViewModel) so turning the phone doesn't wipe it.

### 9. First-run wizard
Mic permission → download models (size shown, Wi-Fi only) → Farsi voice setup → spoken test phrase.
**You:** try it on a freshly-reset install (uninstall first) and say where it's confusing.

### 10–11. Face-to-face UX
Full-screen large-type view of the latest translation (tap an entry); a ⇄ swap button for conversation
languages; switch output lists to RecyclerView.

### 12–13. Farsi pronunciation quality
Claude drafts ~200 everyday sentences with its best romanizations; **you (or a Farsi speaker you trust)**
correct them. That file becomes a test, and failures drive lexicon additions.

### 14–15. Localization and accessibility
Move ~60 hard-coded strings to resources; add Farsi and Ukrainian UI translations (**you:** spot-check),
content descriptions, TalkBack announcements, haptics on hold/release.

### 16. Phrasebook
Star an entry to save it (text + transliteration); saved list works offline and replays audio.

### 17. Dependabot
Add `.github/dependabot.yml` for Gradle and GitHub Actions. **You:** merge its PRs occasionally.

### 18. Refactor
ViewModel + `SpeechCapture`, `TranslationService`, `TtsRouter` classes; opens the door to Compose and
instrumented tests. Best done just before #19.

## Later (v2.x — the big Farsi and conversation upgrades)

### 19. sherpa-onnx inside Babeltrout (v2.0)
- Piper TTS in-app with a downloadable Farsi voice catalog (all six voices, instant switching).
- Silero voice-activity detection + app-owned microphone → conversation mode with no length limit and no beeps.
- SherpaTTS becomes optional. Survives the 2027 verification change.
- APK grows by roughly 20 MB; voices download on demand (~60 MB each).
**You:** device testing; choose default voice.

### 20. Persian G2P
Integrate an ezafe-aware Persian phonemizer (per the Mana-Persian-Piper paper) ahead of Piper, and reuse it
for the pronunciation line. **You:** listen/compare before-and-after samples.

### 21. On-device Persian recognition
Whisper-family Persian model via sherpa-onnx (~150–250 MB download). **You:** compare accuracy against
Google's `fa-IR` on your own speech and a native speaker's.

### 22. Enhanced translation
On phones with Gemini Nano (Pixel 9/10 class) or with a TranslateGemma model, offer "enhanced" translation;
ML Kit remains the fallback. **You:** decide if device-limited features are worth it; confirm Persian support first.

### 23. Spoken-style Farsi
Convert formal written Persian to colloquial (*mikhaaham → mikhaam*) for speech. Research item.

## Someday

### 24. iPhone app
SwiftUI app + Kotlin Multiplatform shared core (`Languages`, `ScriptHeuristics`, `TransliterationEngine`),
ML Kit for iOS translation, Apple Speech for most languages, Whisper for Persian, sherpa-onnx Piper for Farsi voice.
**You:** Apple Developer Program ($99/yr) for TestFlight/installs; an iPhone for testing. Best started after #19.

### 25. Google Play (optional)
**You:** Play developer account ($25), privacy policy page (could live on sweetbriarcomputing.com),
12 testers for 14 days. Claude: remove `REQUEST_INSTALL_PACKAGES` and the Install TTS APK button (or make it a Play flavor).
