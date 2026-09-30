package com.kevin.babeltrout

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Pre-downloads the Android-managed data each language needs, so the first use of a language doesn't
 * stall: the speech engine's offline voice, and (Android 13+) the on-device speech recognition pack.
 * ML Kit translation models and Babeltrout's own Piper voices are downloaded elsewhere.
 */
object SystemLanguagePacks {

    enum class Outcome {
        /** Already on the phone. */
        READY,
        /** Downloaded during this run. */
        DOWNLOADED,
        /** Download started; Android finishes it in the background. */
        DOWNLOADING,
        /** The engine or recognizer doesn't offer this language offline. */
        NOT_OFFERED,
        FAILED,
    }

    /** True if [tts] has an installed voice for [code] that works without a network connection. */
    fun hasOfflineVoice(tts: TextToSpeech, code: String): Boolean =
        runCatching {
            tts.voices.orEmpty().any { voice ->
                Languages.normalizeCode(voice.locale.language) == code &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features &&
                    !voice.isNetworkConnectionRequired
            }
        }.getOrDefault(false)

    /**
     * Makes the engine behind [tts] download its offline voice for [option]. Google TTS only fetches a
     * voice when asked to speak, so this synthesizes the sample phrase to a scratch file (nothing is
     * played), then waits up to [waitMs] for the voice to show up as installed.
     */
    suspend fun prefetchVoice(tts: TextToSpeech, option: LanguageOption, scratchDir: File, waitMs: Long = 90_000): Outcome {
        if (hasOfflineVoice(tts, option.code)) return Outcome.READY

        val locale = Locale.forLanguageTag(option.localeTag)
        val availability = runCatching { tts.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (availability == TextToSpeech.LANG_NOT_SUPPORTED) return Outcome.NOT_OFFERED

        tts.setLanguage(locale)
        val file = File(scratchDir, "voice-prefetch-${option.code}.wav")
        val synthesized = try {
            synthesizeToFile(tts, option.sampleText, file)
        } finally {
            file.delete()
        }

        val installed = withTimeoutOrNull(waitMs) {
            while (!hasOfflineVoice(tts, option.code)) delay(2_000)
            true
        } ?: false
        return when {
            installed -> Outcome.DOWNLOADED
            synthesized -> Outcome.DOWNLOADING
            else -> Outcome.FAILED
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private suspend fun synthesizeToFile(tts: TextToSpeech, text: String, file: File): Boolean {
        val utteranceId = "prefetch-${file.name}"
        return withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { continuation ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit

                    override fun onDone(id: String?) {
                        if (id == utteranceId && continuation.isActive) continuation.resume(true)
                    }

                    override fun onError(id: String?) {
                        if (id == utteranceId && continuation.isActive) continuation.resume(false)
                    }
                })
                if (tts.synthesizeToFile(text, null, file, utteranceId) != TextToSpeech.SUCCESS && continuation.isActive) {
                    continuation.resume(false)
                }
            }
        } ?: false
    }

    /**
     * Downloads Android's on-device speech recognition packs. Create, use and [destroy] on the main
     * thread (a SpeechRecognizer requirement).
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    class RecognitionPacks private constructor(private val recognizer: SpeechRecognizer) {

        suspend fun ensure(option: LanguageOption): Outcome {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, option.localeTag)
            val support = checkSupport(intent) ?: return Outcome.FAILED
            fun List<String>.hasLanguage() = any { Languages.normalizeCode(it) == option.code }

            return when {
                support.installedOnDeviceLanguages.hasLanguage() -> Outcome.READY
                support.pendingOnDeviceLanguages.hasLanguage() -> Outcome.DOWNLOADING
                !support.supportedOnDeviceLanguages.hasLanguage() -> Outcome.NOT_OFFERED
                else -> runCatching {
                    recognizer.triggerModelDownload(intent)
                    Outcome.DOWNLOADING
                }.getOrDefault(Outcome.FAILED)
            }
        }

        private suspend fun checkSupport(intent: Intent): RecognitionSupport? =
            withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine { continuation ->
                    recognizer.checkRecognitionSupport(intent, Runnable::run, object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            if (continuation.isActive) continuation.resume(support)
                        }

                        override fun onError(error: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
                }
            }

        fun destroy() = recognizer.destroy()

        companion object {
            /** Null when the phone has no on-device recognizer. */
            fun create(context: Context): RecognitionPacks? =
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    runCatching { RecognitionPacks(SpeechRecognizer.createOnDeviceSpeechRecognizer(context)) }.getOrNull()
                } else {
                    null
                }
        }
    }
}
