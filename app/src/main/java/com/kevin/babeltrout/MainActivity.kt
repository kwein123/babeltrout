package com.kevin.babeltrout

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.util.TypedValue
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.kevin.babeltrout.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : AppCompatActivity(), RecognitionListener {

    private data class LanguageOption(val code: String, val label: String, val localeTag: String)

    private data class EntryRecord(
        val timestamp: String,
        val sourceLabel: String,
        val sourceText: String,
        val targetLabel: String,
        val targetText: String,
        val transliterationLabel: String,
        val transliterationText: String,
    )

    private data class TransliterationResult(
        val available: Boolean,
        val scriptCode: String,
        val scriptLabel: String,
        val scriptText: String,
        val latinText: String,
    )

    private data class TtsProbeResult(
        val initOk: Boolean,
        val faLanguageAvailable: Boolean,
        val setLanguageOk: Boolean,
        val faVoiceCount: Int,
        val speakOk: Boolean,
        val detail: String,
    )

    private data class TtsEngineRoute(
        val tts: TextToSpeech,
        val enginePackage: String?,
    )

    private enum class UiPage {
        MAIN,
        SUPPORT,
        ICON_PREVIEW,
        CONVERSE,
    }

    private lateinit var binding: ActivityMainBinding

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var converseSpeechRecognizer: SpeechRecognizer
    private lateinit var languageIdentifier: LanguageIdentifier

    private val translatorCache = mutableMapOf<String, Translator>()
    private val remoteModelManager by lazy { RemoteModelManager.getInstance() }
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private val namedEngineTts = mutableMapOf<String, TextToSpeech>()
    private val availableTtsByCode = mutableMapOf<String, Boolean>()
    private val sherpaPackageCandidates = listOf(
        "org.woheller69.ttsengine",
        "org.woheller69.ttsengine.fdroid",
        "org.woheller69.ttsengine.debug",
    )
    private val googleTtsPackageCandidates = listOf(
        "com.google.android.tts",
    )
    private val preferOfflineRecognition = false
    private var lastKnownDefaultTtsEngine = ""

    private val languageOptions = listOf(
        LanguageOption("uk", "Ukrainian", "uk-UA"),
        LanguageOption("ru", "Russian", "ru-RU"),
        LanguageOption("fa", "Farsi", "fa-IR"),
        LanguageOption("ar", "Arabic", "ar-SA"),
        LanguageOption("fr", "French", "fr-FR"),
        LanguageOption("es", "Spanish", "es-ES"),
        LanguageOption("en", "English", "en-US"),
    )

    private val supportedCodes = languageOptions.map { it.code }.toSet()
    private val requiredModelCodes = languageOptions.map { it.code }.toSet()

    private val requiredPairs = buildList {
        languageOptions
            .map { it.code }
            .filter { it != "en" }
            .forEach { code ->
                add("en" to code)
                add(code to "en")
            }
    }

    private val transliterationTargets = setOf("fa", "ar")

    private val modelDownloadConditions = DownloadConditions.Builder().build()

    private val entries = mutableListOf<EntryRecord>()

    private var activeSourceCode: String? = null
    private var activeButton: Button? = null
    private var isListening = false
    private var isProcessing = false
    private var pendingPermissionSourceCode: String? = null
    private var pendingPermissionButtonId: Int? = null
    private var pendingStartConverseAfterPermission = false
    private var pendingApkUri: Uri? = null
    private var isInstallingAssets = false
    private var isCheckingAssets = false
    private var isCheckingVoices = false
    private var isRunningFaDiagnostics = false
    private var isConverseActive = false
    private var isConverseListening = false
    private var isConverseProcessing = false
    private var isConverseSpeaking = false
    private var converseRestartPending = false
    private var lastConverseSourceCode: String? = null
    private var converseTiePrefersCodeA = true
    private var currentPage = UiPage.MAIN
    private var downloadsSummaryText = "Downloads not checked yet."
    private var voicesSummaryText = "Voices not checked yet."
    private var syncingSpeechRateControls = false
    private var syncingOutputSizeControls = false

    private val prefs by lazy { getSharedPreferences("babeltrout_prefs", MODE_PRIVATE) }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val sourceCode = pendingPermissionSourceCode
        val buttonId = pendingPermissionButtonId
        val startConverse = pendingStartConverseAfterPermission
        pendingPermissionSourceCode = null
        pendingPermissionButtonId = null
        pendingStartConverseAfterPermission = false

        if (!granted) {
            setStatus("Microphone permission is required.", isError = true)
            if (isConverseActive) {
                stopConverseMode("Conversation mode stopped: microphone permission is required.")
            }
            return@registerForActivityResult
        }

        val button = buttonId?.let { findViewById<Button>(it) }
        if (button != null) {
            startListening(button, sourceCode)
            return@registerForActivityResult
        }

        if (startConverse) {
            startConverseMode()
        }
    }

    private var pendingExportText = ""

    private val pickTtsApkLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            setStatus("APK selection cancelled.")
            return@registerForActivityResult
        }

        grantReadAccess(uri)
        startTtsApkInstall(uri)
    }

    private val unknownAppsSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val pendingUri = pendingApkUri ?: return@registerForActivityResult
        if (canInstallUnknownApps()) {
            pendingApkUri = null
            launchApkInstaller(pendingUri)
        } else {
            setStatus("Enable Install unknown apps for Babeltrout, then retry.", isError = true)
        }
    }

    private val pickVoiceFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) {
            setStatus("Voice file selection cancelled.")
            return@registerForActivityResult
        }

        uris.forEach { grantReadAccess(it) }

        val fileNames = uris.map { displayNameForUri(it).lowercase(Locale.US) }
        val hasOnnx = fileNames.any { it.endsWith(".onnx") }
        val hasConfig = fileNames.any { it.endsWith(".onnx.json") || it.endsWith(".json") }

        if (!hasOnnx || !hasConfig) {
            setStatus("Select both Piper files: .onnx and .onnx.json.", isError = true)
            return@registerForActivityResult
        }

        shareVoiceFilesToTtsEngine(uris.distinct())
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) {
            setStatus("Export cancelled.")
            return@registerForActivityResult
        }

        runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(pendingExportText.toByteArray())
            }
        }.onSuccess {
            setStatus("Exported transcript to file.")
        }.onFailure { error ->
            setStatus("Export failed: ${error.message}", isError = true)
        }
    }

    private val converseRecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isConverseListening = true
            updateConverseMicIndicator()
            updateConverseStatus("Conversation mic is listening...")
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            if (!isConverseActive) {
                return
            }

            isConverseListening = false
            isConverseProcessing = false
            updateConverseMicIndicator()

            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stopConverseMode("Conversation mode stopped: microphone permission is required.")
                return
            }

            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                handleConverseIdleNoSpeech()
                return
            }

            val message = recognitionErrorMessage(error)
            updateConverseStatus("Mic issue ($message). Retrying...")
            scheduleConverseRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900L else 500L)
        }

        override fun onResults(results: Bundle?) {
            if (!isConverseActive) {
                return
            }

            isConverseListening = false
            updateConverseMicIndicator()
            val codeA = selectedConverseCodeA()
            val codeB = selectedConverseCodeB()
            val transcripts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            val transcript = pickBestTranscriptForPair(transcripts, codeA, codeB)
            val detectedLanguageHint = normalizeCode(results?.getString(SpeechRecognizer.DETECTED_LANGUAGE))
            val detectedLanguageConfidence = results?.getInt(
                SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL,
                SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN
            ) ?: SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN

            if (transcript.isBlank()) {
                handleConverseIdleNoSpeech()
                return
            }

            isConverseProcessing = true
            updateConverseStatus("Processing speech...")

            appScope.launch {
                runCatching {
                    processConverseTranscript(transcript, detectedLanguageHint, detectedLanguageConfidence)
                }.onFailure { error ->
                    updateConverseStatus("Conversation processing failed: ${error.message}")
                }

                isConverseProcessing = false
                if (isConverseActive) {
                    startConverseListening()
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun pickBestTranscriptForPair(candidates: List<String>, codeA: String, codeB: String): String {
        if (candidates.isEmpty()) {
            return ""
        }

        val includesCyrillicLang = codeA in setOf("uk", "ru") || codeB in setOf("uk", "ru")
        if (includesCyrillicLang) {
            candidates.firstOrNull { containsCyrillic(it) }?.let { return it }
        }

        val includesArabicLang = codeA in setOf("fa", "ar") || codeB in setOf("fa", "ar")
        if (includesArabicLang) {
            candidates.firstOrNull { containsArabicScript(it) }?.let { return it }
        }

        return candidates.first()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        languageIdentifier = LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder()
                .setConfidenceThreshold(0.35f)
                .build()
        )

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(this)
        converseSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        converseSpeechRecognizer.setRecognitionListener(converseRecognitionListener)
        lastKnownDefaultTtsEngine = currentDefaultTtsEngine()
        initTextToSpeech()

        setupTargetSpinner()
        setupConversePage()
        setupSpeechRateControl()
        setupOutputSizeControl()
        setupHoldButtons()
        setupUtilityButtons()
        setupBackHandling()
        showPage(UiPage.MAIN)
        updateAssetsSummary()

        maybeInstallAssetsOnFirstRun()
        checkDownloadedAssets(manual = false)
        checkVoiceSupport(manual = false)
        setStatus("Ready.")
    }

    private fun applySystemBarInsets() {
        val baseLeft = binding.root.paddingLeft
        val baseTop = binding.root.paddingTop
        val baseRight = binding.root.paddingRight
        val baseBottom = binding.root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(baseLeft, baseTop + bars.top, baseRight, baseBottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupTargetSpinner() {
        val labels = languageOptions.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.targetLanguageSpinner.adapter = adapter
        binding.targetLanguageSpinner.setSelection(languageOptions.indexOfFirst { it.code == "uk" }.coerceAtLeast(0))
    }

    private fun setupConversePage() {
        val labels = languageOptions.map { it.label }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.converseLangASpinner.adapter = adapter
        binding.converseLangBSpinner.adapter = adapter

        binding.converseLangASpinner.setSelection(languageOptions.indexOfFirst { it.code == "en" }.coerceAtLeast(0))
        binding.converseLangBSpinner.setSelection(languageOptions.indexOfFirst { it.code == "fa" }.coerceAtLeast(0))

        binding.btnOpenConversePage.setOnClickListener {
            showPage(UiPage.CONVERSE)
        }

        binding.linkMainFromConverse.setOnClickListener {
            showPage(UiPage.MAIN)
        }

        binding.btnConverseToggle.setOnClickListener {
            if (isConverseActive) {
                stopConverseMode("Conversation mode stopped.")
            } else {
                startConverseMode()
            }
        }

        val autoRestartEnabled = prefs.getBoolean("converse_auto_restart_idle", true)
        binding.switchConverseAutoRestart.isChecked = autoRestartEnabled
        binding.switchConverseAutoRestart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("converse_auto_restart_idle", isChecked).apply()
            val modeText = if (isChecked) "on" else "off"
            setStatus("Conversation idle auto-restart is $modeText.")
        }

        binding.btnClearConverse.setOnClickListener {
            binding.converseOutputContainer.removeAllViews()
            setStatus("Conversation output cleared.")
        }

        updateConverseToggleUi()
        updateConverseStatus("Conversation mic is off.")
    }

    private fun setupSpeechRateControl() {
        syncingSpeechRateControls = true
        binding.supportSpeechRateSeek.progress = binding.speechRateSeek.progress
        syncingSpeechRateControls = false
        updateSpeechRateLabel()

        binding.speechRateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!syncingSpeechRateControls) {
                    syncingSpeechRateControls = true
                    binding.supportSpeechRateSeek.progress = progress
                    syncingSpeechRateControls = false
                }
                updateSpeechRateLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.supportSpeechRateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!syncingSpeechRateControls) {
                    syncingSpeechRateControls = true
                    binding.speechRateSeek.progress = progress
                    syncingSpeechRateControls = false
                }
                updateSpeechRateLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun setupOutputSizeControl() {
        syncingOutputSizeControls = true
        binding.supportOutputSizeSeek.progress = binding.outputSizeSeek.progress
        syncingOutputSizeControls = false
        updateOutputSizeLabel()

        binding.outputSizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!syncingOutputSizeControls) {
                    syncingOutputSizeControls = true
                    binding.supportOutputSizeSeek.progress = progress
                    syncingOutputSizeControls = false
                }
                updateOutputSizeLabel()
                applyOutputSizeToAllEntries()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.supportOutputSizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!syncingOutputSizeControls) {
                    syncingOutputSizeControls = true
                    binding.outputSizeSeek.progress = progress
                    syncingOutputSizeControls = false
                }
                updateOutputSizeLabel()
                applyOutputSizeToAllEntries()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun setupHoldButtons() {
        bindHoldButton(binding.btnHoldAuto, null)
        bindHoldButton(binding.btnHoldEn, "en")
        bindHoldButton(binding.btnHoldFa, "fa")
        bindHoldButton(binding.btnHoldUk, "uk")
        bindHoldButton(binding.btnHoldRu, "ru")
        bindHoldButton(binding.btnHoldAr, "ar")
        bindHoldButton(binding.btnHoldFr, "fr")
        bindHoldButton(binding.btnHoldEs, "es")
    }

    private fun bindHoldButton(button: Button, sourceCode: String?) {
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onHoldDown(button, sourceCode)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onHoldUp(button)
                    true
                }

                else -> false
            }
        }
    }

    private fun onHoldDown(button: Button, sourceCode: String?) {
        if (isConverseActive) {
            setStatus("Conversation mode is active. Turn off Conversation Mic before push-to-talk.")
            return
        }

        if (isListening || isProcessing) {
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Speech recognition is unavailable on this device.", isError = true)
            return
        }

        if (!hasAudioPermission()) {
            pendingPermissionSourceCode = sourceCode
            pendingPermissionButtonId = button.id
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        startListening(button, sourceCode)
    }

    private fun onHoldUp(button: Button) {
        if (!isListening) {
            return
        }

        if (activeButton != button) {
            return
        }

        stopListening()
    }

    private fun startListening(button: Button, sourceCode: String?) {
        if (isListening || isProcessing) {
            return
        }

        val recognitionIntent = buildRecognitionIntent(sourceCode)

        activeSourceCode = sourceCode
        activeButton = button
        isListening = true

        button.tag = button.text.toString()
        button.text = "Release to Process"

        setStatus(
            if (sourceCode == null) "Listening (auto detect)..."
            else "Listening (${labelForCode(sourceCode)})..."
        )

        runCatching {
            speechRecognizer.startListening(recognitionIntent)
        }.onFailure { error ->
            resetCaptureState()
            setStatus("Unable to start listening: ${error.message}", isError = true)
        }
    }

    private fun stopListening() {
        if (!isListening) {
            return
        }

        isListening = false
        isProcessing = true

        activeButton?.let { button ->
            val defaultText = button.tag as? String
            if (defaultText != null) {
                button.text = defaultText
            }
            button.tag = null
        }

        setStatus("Processing speech...")
        runCatching { speechRecognizer.stopListening() }
    }

    private fun buildRecognitionIntent(sourceCode: String?): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOfflineRecognition)
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)

        if (sourceCode != null) {
            val recognitionTag = recognitionTagForCode(sourceCode)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionTag)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, recognitionTag)
        } else {
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "und")
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "und")
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE)
            val allowedTags = ArrayList(languageOptions.map { it.localeTag })
            intent.putStringArrayListExtra(
                RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                allowedTags
            )
            intent.putStringArrayListExtra(
                RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                allowedTags
            )
        }

        return intent
    }

    private fun selectedConverseCodeA(): String {
        val index = binding.converseLangASpinner.selectedItemPosition
        return languageOptions.getOrNull(index)?.code ?: "en"
    }

    private fun selectedConverseCodeB(): String {
        val index = binding.converseLangBSpinner.selectedItemPosition
        return languageOptions.getOrNull(index)?.code ?: "fa"
    }

    private fun buildConverseIntent(codeA: String, codeB: String): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOfflineRecognition)
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        intent.putExtra("android.speech.extra.DICTATION_MODE", true)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "und")
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "und")
        intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
        intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE)

        val allowedTags = arrayListOf(localeTagForCode(codeA), localeTagForCode(codeB))

        intent.putStringArrayListExtra(
            RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
            allowedTags
        )
        intent.putStringArrayListExtra(
            RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
            allowedTags
        )
        return intent
    }

    private fun startConverseMode() {
        if (isConverseActive) {
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Speech recognition is unavailable on this device.", isError = true)
            return
        }

        val codeA = selectedConverseCodeA()
        val codeB = selectedConverseCodeB()
        if (codeA == codeB) {
            setStatus("Choose two different languages for Conversation Mode.", isError = true)
            return
        }

        if (!hasAudioPermission()) {
            pendingStartConverseAfterPermission = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        isConverseActive = true
        converseRestartPending = false
        lastConverseSourceCode = null
        converseTiePrefersCodeA = true
        updateConverseToggleUi()
        updateConverseStatus("Conversation mic is starting...")
        setStatus("Conversation mode started: ${labelForCode(codeA)} <-> ${labelForCode(codeB)}")
        startConverseListening()
    }

    private fun stopConverseMode(message: String) {
        if (!isConverseActive && !isConverseListening && !isConverseProcessing) {
            updateConverseToggleUi()
            updateConverseStatus("Conversation mic is off.")
            if (message.isNotBlank()) {
                setStatus(message)
            }
            return
        }

        isConverseActive = false
        isConverseListening = false
        isConverseProcessing = false
        isConverseSpeaking = false
        converseRestartPending = false
        lastConverseSourceCode = null
        runCatching { converseSpeechRecognizer.cancel() }
        updateConverseToggleUi()
        updateConverseStatus("Conversation mic is off.")
        if (message.isNotBlank()) {
            setStatus(message)
        }
    }

    private fun updateConverseToggleUi() {
        binding.btnConverseToggle.text = if (isConverseActive) "Conversation Mic: ON" else "Conversation Mic: OFF"
        updateConverseMicIndicator()
    }

    private fun updateConverseMicIndicator() {
        val micIsListening = isConverseActive && isConverseListening
        binding.conversationMicIndicatorLight.setBackgroundResource(
            if (micIsListening) R.drawable.bg_mic_light_on else R.drawable.bg_mic_light_off
        )
        binding.conversationMicIndicatorLabel.text = when {
            micIsListening -> "Mic ON"
            isConverseActive -> "Mic Paused"
            else -> "Mic OFF"
        }
    }

    private fun updateConverseStatus(message: String) {
        binding.converseStatusText.text = message
    }

    private fun scheduleConverseRestart(delayMs: Long) {
        if (!isConverseActive || converseRestartPending) {
            return
        }

        converseRestartPending = true
        appScope.launch {
            delay(delayMs)
            converseRestartPending = false
            if (isConverseActive) {
                startConverseListening()
            }
        }
    }

    private fun handleConverseIdleNoSpeech() {
        if (!isConverseActive) {
            return
        }

        if (binding.switchConverseAutoRestart.isChecked) {
            updateConverseStatus("Idle timeout. Restarting conversation mic...")
            scheduleConverseRestart(2800L)
            return
        }

        stopConverseMode("Conversation mic paused after idle timeout (auto-restart is off).")
    }

    private fun startConverseListening() {
        if (!isConverseActive || isConverseListening || isConverseProcessing || isConverseSpeaking) {
            return
        }

        val codeA = selectedConverseCodeA()
        val codeB = selectedConverseCodeB()
        if (codeA == codeB) {
            stopConverseMode("Conversation mode stopped: choose two different languages.")
            return
        }

        isConverseListening = false
        updateConverseMicIndicator()
        val intent = buildConverseIntent(codeA, codeB)
        runCatching {
            converseSpeechRecognizer.startListening(intent)
        }.onFailure { error ->
            isConverseListening = false
            updateConverseMicIndicator()
            updateConverseStatus("Unable to start conversation mic: ${error.message}")
            scheduleConverseRestart(350L)
        }
    }

    private suspend fun processConverseTranscript(
        transcript: String,
        detectedLanguageHint: String,
        detectedLanguageConfidence: Int,
    ) {
        val codeA = selectedConverseCodeA()
        val codeB = selectedConverseCodeB()
        if (codeA == codeB) {
            error("Conversation mode requires two different languages.")
        }

        val sourceCode = detectSourceWithinPair(
            transcript = transcript,
            codeA = codeA,
            codeB = codeB,
            detectedLanguageHint = detectedLanguageHint,
            detectedLanguageConfidence = detectedLanguageConfidence,
        )
        lastConverseSourceCode = sourceCode
        val targetCode = if (sourceCode == codeA) codeB else codeA
        val sourceTextForDisplay = normalizeConverseSourceText(transcript, sourceCode)
        val (targetText, route) = translateText(sourceTextForDisplay, sourceCode, targetCode)
        val (transliteratedText, transliteratedCode) = buildConverseTransliteration(
            sourceText = sourceTextForDisplay,
            sourceCode = sourceCode,
            targetText = targetText,
            targetCode = targetCode,
        )

        appendConverseOutput(
            sourceCode = sourceCode,
            sourceText = sourceTextForDisplay,
            targetCode = targetCode,
            targetText = targetText,
            transliteratedText = transliteratedText,
            transliteratedCode = transliteratedCode,
        )
        speakTargetAndWait(targetText, targetCode)
        val hintInfo = if (detectedLanguageHint == sourceCode) {
            " | speech engine: ${labelForCode(sourceCode)}"
        } else {
            ""
        }
        updateConverseStatus(
            "Detected ${labelForCode(sourceCode)} -> ${labelForCode(targetCode)} (${route.joinToString(" -> ")})$hintInfo"
        )
    }

    private suspend fun detectSourceWithinPair(
        transcript: String,
        codeA: String,
        codeB: String,
        detectedLanguageHint: String,
        detectedLanguageConfidence: Int,
    ): String {
        val normalizedHint = normalizeCode(detectedLanguageHint)
        if (normalizedHint == codeA || normalizedHint == codeB) {
            val hintMatchesScript = when {
                containsCyrillic(transcript) -> normalizedHint == "uk" || normalizedHint == "ru"
                containsArabicScript(transcript) -> normalizedHint == "fa" || normalizedHint == "ar"
                else -> false
            }
            val hintConfident = detectedLanguageConfidence >= SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT
            if (hintConfident || hintMatchesScript) {
                return normalizedHint
            }
        }

        val detected = detectSourceLanguage(transcript)
        if (detected == codeA || detected == codeB) {
            return detected
        }

        if (containsArabicScript(transcript)) {
            if (setOf(codeA, codeB) == setOf("fa", "ar")) {
                return if (looksFarsi(transcript)) "fa" else "ar"
            }
            if (codeA == "fa" || codeB == "fa") {
                return "fa"
            }
            if (codeA == "ar" || codeB == "ar") {
                return "ar"
            }
        }

        if (containsCyrillic(transcript)) {
            if (setOf(codeA, codeB) == setOf("uk", "ru")) {
                return if (looksUkrainian(transcript)) "uk" else "ru"
            }
            if (codeA == "uk" || codeB == "uk") {
                return "uk"
            }
            if (codeA == "ru" || codeB == "ru") {
                return "ru"
            }
        }

        val scoreA = scoreLanguageForTranscript(transcript, codeA)
        val scoreB = scoreLanguageForTranscript(transcript, codeB)
        if (scoreA == scoreB) {
            if (normalizedHint == codeA || normalizedHint == codeB) {
                return normalizedHint
            }

            if (lastConverseSourceCode == codeA) {
                return codeB
            }
            if (lastConverseSourceCode == codeB) {
                return codeA
            }

            val chosen = if (converseTiePrefersCodeA) codeA else codeB
            converseTiePrefersCodeA = !converseTiePrefersCodeA
            return chosen
        }
        return if (scoreB > scoreA) codeB else codeA
    }

    private fun scoreLanguageForTranscript(transcript: String, code: String): Int {
        return when (normalizeCode(code)) {
            "fa" -> when {
                looksFarsi(transcript) -> 3
                containsArabicScript(transcript) -> 1
                else -> 0
            }

            "ar" -> when {
                containsArabicScript(transcript) && !looksFarsi(transcript) -> 3
                containsArabicScript(transcript) -> 1
                else -> 0
            }

            "uk" -> when {
                looksUkrainian(transcript) -> 3
                containsCyrillic(transcript) -> 1
                else -> 0
            }

            "ru" -> when {
                containsCyrillic(transcript) && !looksUkrainian(transcript) -> 3
                containsCyrillic(transcript) -> 1
                else -> 0
            }

            "fr" -> if (looksFrench(transcript.lowercase(Locale.US))) 2 else 0
            "es" -> if (looksSpanish(transcript.lowercase(Locale.US))) 2 else 0
            "en" -> {
                val lowered = " ${transcript.lowercase(Locale.US)} "
                val markers = listOf(
                    " the ", " and ", " is ", " are ", " to ", " of ",
                    " hello ", " hi ", " yes ", " no ", " please ", " thanks ",
                    " thank ", " good ", " morning ", " evening ", " okay ", " ok ",
                    " how ", " what ", " where ", " why ", " who ", " when "
                )
                if (markers.any { lowered.contains(it) }) 2 else 0
            }

            else -> 0
        }
    }

    private fun transliterateTextToScript(text: String, textCode: String, targetScriptCode: String): String {
        val normalizedTextCode = normalizeCode(textCode)
        val latin = when (normalizedTextCode) {
            "fa", "ar" -> TransliterationEngine.arabicScriptToLatin(text)
            "uk", "ru" -> TransliterationEngine.cyrillicToLatin(text, normalizedTextCode)
            else -> text
        }

        return TransliterationEngine.latinToScript(latin, normalizeCode(targetScriptCode)).ifBlank { text }
    }

    private fun normalizeConverseSourceText(transcript: String, sourceCode: String): String {
        val normalizedSourceCode = normalizeCode(sourceCode)
        val sourceUsesArabicScript = normalizedSourceCode == "fa" || normalizedSourceCode == "ar"
        val sourceUsesCyrillic = normalizedSourceCode == "uk" || normalizedSourceCode == "ru"
        val hasSourceScript = when {
            sourceUsesArabicScript -> containsArabicScript(transcript)
            sourceUsesCyrillic -> containsCyrillic(transcript)
            else -> true
        }

        if (hasSourceScript || !transcript.any { it.isLetter() }) {
            return transcript
        }

        val converted = TransliterationEngine.latinToScript(transcript, normalizedSourceCode)
        return if (converted.isBlank()) transcript else converted
    }

    private fun buildConverseTransliteration(
        sourceText: String,
        sourceCode: String,
        targetText: String,
        targetCode: String,
    ): Pair<String, String> {
        val normalizedSourceCode = normalizeCode(sourceCode)
        val normalizedTargetCode = normalizeCode(targetCode)
        val nonLatinSourceCodes = setOf("fa", "ar", "uk", "ru")
        val latinTargetCodes = setOf("en", "fr", "es")

        // For non-Latin source languages translated into a Latin-script target language,
        // show pronunciation as source text transliterated into Latin characters.
        if (normalizedSourceCode in nonLatinSourceCodes && normalizedTargetCode in latinTargetCodes) {
            val latin = when (normalizedSourceCode) {
                "fa", "ar" -> TransliterationEngine.arabicScriptToLatin(sourceText)
                "uk", "ru" -> TransliterationEngine.cyrillicToLatin(sourceText, normalizedSourceCode)
                else -> sourceText
            }.ifBlank { sourceText }
            return latin to normalizedTargetCode
        }

        val transliterated = transliterateTextToScript(targetText, normalizedTargetCode, normalizedSourceCode)
        return transliterated to normalizedSourceCode
    }

    private fun appendConverseOutput(
        sourceCode: String,
        sourceText: String,
        targetCode: String,
        targetText: String,
        transliteratedText: String,
        transliteratedCode: String,
    ) {
        val view = layoutInflater.inflate(R.layout.item_converse_entry, binding.converseOutputContainer, false)
        val timeLine = view.findViewById<TextView>(R.id.converseTimeText)
        val sourceLine = view.findViewById<TextView>(R.id.converseSourceText)
        val targetLine = view.findViewById<TextView>(R.id.converseTargetText)
        val translitLine = view.findViewById<TextView>(R.id.converseTranslitText)

        val sourceLabel = labelForCode(sourceCode)
        val targetLabel = labelForCode(targetCode)
        val translitLabel = labelForCode(transliteratedCode)

        timeLine.text = LocalTime.now().withNano(0).toString()
        sourceLine.text = "$sourceLabel: $sourceText"
        targetLine.text = "$targetLabel: $targetText"
        translitLine.text = "Transliteration (${translitLabel} script): $transliteratedText"

        applyDirection(sourceLine, sourceCode)
        applyDirection(targetLine, targetCode)
        applyDirection(translitLine, transliteratedCode)

        applyOutputSizeToConverseEntryView(view)

        binding.converseOutputContainer.addView(view, 0)
    }

    private fun setupUtilityButtons() {
        binding.linkSupportPage.setOnClickListener {
            showPage(UiPage.SUPPORT)
        }

        binding.linkMainPage.setOnClickListener {
            showPage(UiPage.MAIN)
        }

        binding.btnOpenIconPreview.setOnClickListener {
            showPage(UiPage.ICON_PREVIEW)
        }

        binding.linkSupportFromIconPreview.setOnClickListener {
            showPage(UiPage.SUPPORT)
        }

        binding.btnInstallAssets.setOnClickListener {
            installMissingAssets(manual = true)
        }

        binding.btnCheckAssets.setOnClickListener {
            checkDownloadedAssets(manual = true)
        }

        binding.btnCheckVoices.setOnClickListener {
            checkVoiceSupport(manual = true)
        }

        binding.btnVoiceSettings.setOnClickListener {
            openVoiceSettings()
        }

        binding.btnInstallTtsApk.setOnClickListener {
            pickTtsApkLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
        }

        binding.btnImportVoiceFiles.setOnClickListener {
            pickVoiceFilesLauncher.launch(arrayOf("*/*"))
        }

        binding.btnOpenTtsApp.setOnClickListener {
            openSherpaTtsApp()
        }

        binding.btnDiagnoseFaTts.setOnClickListener {
            runFarsiTtsDiagnostics()
        }

        binding.btnExport.setOnClickListener {
            exportTranscript()
        }

        binding.btnClear.setOnClickListener {
            entries.clear()
            binding.outputContainer.removeAllViews()
            binding.converseOutputContainer.removeAllViews()
            setStatus("All output cleared.")
        }
    }

    private fun showPage(page: UiPage) {
        if (currentPage == UiPage.CONVERSE && page != UiPage.CONVERSE && isConverseActive) {
            stopConverseMode("Conversation mode paused.")
        }

        currentPage = page
        binding.mainPageScroll.isVisible = page == UiPage.MAIN
        binding.supportPageScroll.isVisible = page == UiPage.SUPPORT
        binding.iconPreviewPageScroll.isVisible = page == UiPage.ICON_PREVIEW
        binding.conversePageScroll.isVisible = page == UiPage.CONVERSE
    }

    private fun startTtsApkInstall(apkUri: Uri) {
        if (!canInstallUnknownApps()) {
            pendingApkUri = apkUri
            val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }

            runCatching { unknownAppsSettingsLauncher.launch(permissionIntent) }
                .onSuccess {
                    setStatus("Allow installs for Babeltrout, then return to continue.")
                }
                .onFailure { error ->
                    setStatus("Unable to open unknown-app settings: ${error.message}", isError = true)
                }
            return
        }

        launchApkInstaller(apkUri)
    }

    private fun launchApkInstaller(apkUri: Uri) {
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        runCatching { startActivity(installIntent) }
            .onSuccess {
                setStatus("APK installer opened. Finish installing SherpaTTS, then return.")
            }
            .onFailure { error ->
                setStatus("Unable to start APK installer: ${error.message}", isError = true)
            }
    }

    private fun canInstallUnknownApps(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()
    }

    private fun isLikelySherpaPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) {
            return false
        }

        val lowered = packageName.lowercase(Locale.US)
        return lowered.contains("woheller") || lowered.contains("sherpa")
    }

    private fun isLikelyGoogleTtsPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) {
            return false
        }

        val lowered = packageName.lowercase(Locale.US)
        return lowered.contains("google") && (lowered.contains("tts") || lowered.contains("speech"))
    }

    private fun resolveSherpaPackageName(): String? {
        val defaultEngine = currentDefaultTtsEngine()
        if (isLikelySherpaPackage(defaultEngine)) {
            return defaultEngine
        }

        val engineBased = textToSpeech?.engines
            ?.firstOrNull { info ->
                val label = info.label.toString().lowercase(Locale.US)
                isLikelySherpaPackage(info.name) || label.contains("sherpa")
            }
            ?.name
        if (!engineBased.isNullOrBlank()) {
            return engineBased
        }

        return sherpaPackageCandidates.firstOrNull { candidate ->
            installedPackageVersion(candidate) != null || packageManager.getLaunchIntentForPackage(candidate) != null
        }
    }

    private fun resolveGoogleTtsPackageName(): String? {
        val defaultEngine = currentDefaultTtsEngine()
        if (isLikelyGoogleTtsPackage(defaultEngine)) {
            return defaultEngine
        }

        val engineBased = textToSpeech?.engines
            ?.firstOrNull { info ->
                val label = info.label.toString().lowercase(Locale.US)
                isLikelyGoogleTtsPackage(info.name) || (label.contains("google") && (label.contains("tts") || label.contains("speech")))
            }
            ?.name
        if (!engineBased.isNullOrBlank()) {
            return engineBased
        }

        return googleTtsPackageCandidates.firstOrNull { candidate ->
            installedPackageVersion(candidate) != null || packageManager.getLaunchIntentForPackage(candidate) != null
        }
    }

    private fun engineLabelForPackage(packageName: String?): String {
        if (isLikelySherpaPackage(packageName)) {
            return "SherpaTTS"
        }
        if (isLikelyGoogleTtsPackage(packageName)) {
            return "Google TTS"
        }
        return if (packageName.isNullOrBlank()) "default engine" else packageName
    }

    private suspend fun getOrCreateNamedEngineTts(enginePackage: String): TextToSpeech? {
        namedEngineTts[enginePackage]?.let { return it }

        val newTts = createTtsForProbe(enginePackage) ?: return null
        namedEngineTts[enginePackage] = newTts
        return newTts
    }

    private suspend fun resolveRouteForOutputCode(code: String): TtsEngineRoute? {
        val normalizedCode = normalizeCode(code)
        val defaultEngine = currentDefaultTtsEngine().ifBlank { null }
        val defaultTts = if (ttsReady) textToSpeech else null

        var preferredRoute: TtsEngineRoute? = null
        val preferredEngine = if (normalizedCode == "fa") {
            resolveSherpaPackageName()
        } else {
            resolveGoogleTtsPackageName()
        }

        if (!preferredEngine.isNullOrBlank()) {
            if (preferredEngine == defaultEngine && defaultTts != null) {
                preferredRoute = TtsEngineRoute(defaultTts, defaultEngine)
            } else {
                val preferredTts = getOrCreateNamedEngineTts(preferredEngine)
                if (preferredTts != null) {
                    preferredRoute = TtsEngineRoute(preferredTts, preferredEngine)
                }
            }

            if (preferredRoute != null && isVoiceAvailable(preferredRoute.tts, normalizedCode)) {
                return preferredRoute
            }
        }

        if (defaultTts != null && isVoiceAvailable(defaultTts, normalizedCode)) {
            return TtsEngineRoute(defaultTts, defaultEngine)
        }

        return preferredRoute
    }

    private fun openSherpaTtsApp() {
        val sherpaPackage = resolveSherpaPackageName()
        if (sherpaPackage == null) {
            setStatus("SherpaTTS package not detected. Tap Install TTS APK first.", isError = true)
            return
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(sherpaPackage)
        if (launchIntent == null) {
            openVoiceSettings()
            setStatus("SherpaTTS detected as an engine but has no launcher. Opened Voice Settings.")
            return
        }

        runCatching { startActivity(launchIntent) }
            .onSuccess {
                setStatus("Opened SherpaTTS.")
            }
            .onFailure { error ->
                setStatus("Unable to open SherpaTTS: ${error.message}", isError = true)
            }
    }

    private fun shareVoiceFilesToTtsEngine(uris: List<Uri>) {
        val streams = ArrayList(uris)

        fun baseIntent(): Intent {
            return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val sherpaPackage = resolveSherpaPackageName()
        if (!sherpaPackage.isNullOrBlank()) {
            val directIntent = baseIntent().apply { setPackage(sherpaPackage) }
            if (runCatching { startActivity(directIntent) }.isSuccess) {
                setStatus("Shared files to SherpaTTS. Import/enable the voice inside SherpaTTS.")
                return
            }
        }

        val chooserIntent = Intent.createChooser(baseIntent(), "Share Piper voice files")
        runCatching { startActivity(chooserIntent) }
            .onSuccess {
                setStatus("Shared voice files. Pick your TTS engine and import there.")
            }
            .onFailure { error ->
                setStatus("Unable to share voice files: ${error.message}", isError = true)
            }
    }

    private fun grantReadAccess(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun displayNameForUri(uri: Uri): String {
        if (uri.scheme != "content") {
            return uri.lastPathSegment ?: ""
        }

        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index) ?: ""
            }
        }

        return uri.lastPathSegment ?: ""
    }

    private fun runFarsiTtsDiagnostics() {
        if (isRunningFaDiagnostics) {
            setStatus("Farsi TTS diagnostics already running.")
            return
        }

        isRunningFaDiagnostics = true
        binding.btnDiagnoseFaTts.isEnabled = false
        setStatus("Running Farsi TTS diagnostics...")

        appScope.launch {
            runCatching {
                val report = diagnoseFarsiTtsReport()
                showFarsiDiagnosticsDialog(report)
                setStatus("Farsi TTS diagnostics complete.")
            }.onFailure { error ->
                setStatus("Farsi diagnostics failed: ${error.message}", isError = true)
            }

            isRunningFaDiagnostics = false
            binding.btnDiagnoseFaTts.isEnabled = true
        }
    }

    private suspend fun diagnoseFarsiTtsReport(): String {
        val lines = mutableListOf<String>()
        val issues = mutableListOf<String>()
        val fixes = mutableListOf<String>()

        lines += "Farsi TTS Diagnostics"
        lines += "Time: ${LocalDate.now()} ${LocalTime.now().withNano(0)}"
        lines += ""

        val audioManager = getSystemService(AudioManager::class.java)
        val mediaVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        val mediaMaxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: -1
        val ringerMode = when (audioManager?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            else -> "unknown"
        }

        lines += "Media volume: ${if (mediaVolume >= 0 && mediaMaxVolume >= 0) "$mediaVolume/$mediaMaxVolume" else "unknown"}"
        lines += "Ringer mode: $ringerMode"
        if (mediaVolume == 0) {
            issues += "Media volume is zero."
            fixes += "Increase media volume before running Farsi playback tests."
        }

        val sherpaPackage = resolveSherpaPackageName()
        lines += "Sherpa package detected: ${sherpaPackage ?: "not detected"}"
        val sherpaVersion = sherpaPackage?.let { installedPackageVersion(it) }

        if (sherpaPackage == null) {
            lines += "SherpaTTS installed: no"
            issues += "SherpaTTS package was not detected."
            fixes += "Tap Install TTS APK and install the SherpaTTS APK."
        } else if (sherpaVersion.isNullOrBlank()) {
            lines += "SherpaTTS installed: yes (version unreadable)"
        } else {
            lines += "SherpaTTS installed: yes (version $sherpaVersion)"
        }

        val defaultEngine = currentDefaultTtsEngine()
        lines += "Default TTS engine: ${if (defaultEngine.isBlank()) "unknown" else defaultEngine}"

        val googlePackage = resolveGoogleTtsPackageName()
        lines += "Google TTS package detected: ${googlePackage ?: "not detected"}"
        lines += "Routing policy: Farsi -> SherpaTTS, Other languages -> Google TTS, fallback -> default engine."

        val defaultProbe = probeTtsEngine(enginePackage = null)
        lines += ""
        lines += "Default engine probe:"
        lines.addAll(formatProbe(defaultProbe))

        if (!defaultProbe.initOk) {
            issues += "Default TTS engine failed to initialize."
            fixes += "Restart the app/device and verify Android TTS is functioning."
        }

        if (sherpaPackage != null) {
            val sherpaProbe = probeTtsEngine(enginePackage = sherpaPackage)
            lines += ""
            lines += "SherpaTTS probe:"
            lines.addAll(formatProbe(sherpaProbe))

            if (!sherpaProbe.initOk) {
                issues += "SherpaTTS failed to initialize as a TTS engine."
                fixes += "Open SherpaTTS once and finish its setup flow, then rerun diagnostics."
            } else {
                if (!sherpaProbe.faLanguageAvailable) {
                    issues += "SherpaTTS does not currently expose a Farsi voice."
                    fixes += "Tap Import Voice Files and select both fa_IR-amir-medium.onnx and fa_IR-amir-medium.onnx.json, then enable that voice in SherpaTTS."
                }
                if (sherpaProbe.faLanguageAvailable && sherpaProbe.faVoiceCount == 0) {
                    issues += "SherpaTTS shows Farsi language support but no Farsi voice entries."
                    fixes += "Inside SherpaTTS, select fa_IR-amir-medium as the active/default voice."
                }
                if (sherpaProbe.faLanguageAvailable && !sherpaProbe.speakOk) {
                    issues += "SherpaTTS failed the Farsi speak callback test."
                    fixes += "Inside SherpaTTS, run its own voice test and verify the selected Farsi voice plays audio."
                }
            }
        }

        lines += ""
        if (issues.isEmpty()) {
            lines += "Result: PASS"
            lines += "All required Farsi TTS pieces appear to be in place and functioning."
        } else {
            lines += "Result: FAIL (${issues.distinct().size} issue(s))"
            lines += "Missing or broken pieces:"
            issues.distinct().forEachIndexed { index, issue ->
                lines += "${index + 1}. $issue"
            }
            lines += ""
            lines += "How to fix:"
            fixes.distinct().forEachIndexed { index, fix ->
                lines += "${index + 1}. $fix"
            }
        }

        return lines.joinToString("\n")
    }

    private fun formatProbe(probe: TtsProbeResult): List<String> {
        val lines = mutableListOf<String>()
        lines += "- Init: ${if (probe.initOk) "ok" else "failed"}"
        lines += "- Farsi available: ${if (probe.faLanguageAvailable) "yes" else "no"}"
        lines += "- setLanguage(fa): ${if (probe.setLanguageOk) "ok" else "failed"}"
        lines += "- Farsi voices found: ${probe.faVoiceCount}"
        lines += "- Speak callback: ${if (probe.speakOk) "ok" else "failed"}"
        if (probe.detail.isNotBlank()) {
            lines += "- Detail: ${probe.detail}"
        }
        return lines
    }

    private suspend fun probeTtsEngine(enginePackage: String?): TtsProbeResult {
        val tts = createTtsForProbe(enginePackage) ?: return TtsProbeResult(
            initOk = false,
            faLanguageAvailable = false,
            setLanguageOk = false,
            faVoiceCount = 0,
            speakOk = false,
            detail = "Initialization failed or timed out"
        )

        return try {
            val faIr = Locale.forLanguageTag("fa-IR")
            val fa = Locale.forLanguageTag("fa")

            val faIrAvailability = tts.isLanguageAvailable(faIr)
            val faAvailability = tts.isLanguageAvailable(fa)
            val faLanguageAvailable = isLanguageResultSupported(faIrAvailability) ||
                isLanguageResultSupported(faAvailability)

            var setLanguageResult = TextToSpeech.ERROR
            if (isLanguageResultSupported(faIrAvailability)) {
                setLanguageResult = tts.setLanguage(faIr)
            }
            if (!isLanguageResultSupported(setLanguageResult) && isLanguageResultSupported(faAvailability)) {
                setLanguageResult = tts.setLanguage(fa)
            }
            val setLanguageOk = isLanguageResultSupported(setLanguageResult)

            val faVoices = (tts.voices ?: emptySet())
                .filter { normalizeCode(it.locale.toLanguageTag()) == "fa" }
                .sortedBy { if (it.isNetworkConnectionRequired) 1 else 0 }

            faVoices.firstOrNull()?.let { tts.voice = it }

            val speakResult = if (setLanguageOk) {
                runTtsSpeakProbe(tts, "سلام، این یک آزمایش صدا است")
            } else {
                false to "Language setup did not succeed"
            }

            TtsProbeResult(
                initOk = true,
                faLanguageAvailable = faLanguageAvailable,
                setLanguageOk = setLanguageOk,
                faVoiceCount = faVoices.size,
                speakOk = speakResult.first,
                detail = "fa-IR=$faIrAvailability, fa=$faAvailability, setLanguage=$setLanguageResult, probe=${speakResult.second}"
            )
        } finally {
            tts.stop()
            tts.shutdown()
        }
    }

    private fun isLanguageResultSupported(result: Int): Boolean {
        return result >= TextToSpeech.LANG_AVAILABLE
    }

    private suspend fun createTtsForProbe(enginePackage: String?): TextToSpeech? {
        return withTimeoutOrNull(8000) {
            suspendCancellableCoroutine { continuation ->
                var ttsRef: TextToSpeech? = null
                val initListener = TextToSpeech.OnInitListener { status ->
                    if (!continuation.isActive) {
                        ttsRef?.shutdown()
                        return@OnInitListener
                    }

                    if (status == TextToSpeech.SUCCESS && ttsRef != null) {
                        continuation.resume(ttsRef)
                    } else {
                        ttsRef?.shutdown()
                        continuation.resume(null)
                    }
                }

                ttsRef = if (enginePackage.isNullOrBlank()) {
                    TextToSpeech(this@MainActivity, initListener)
                } else {
                    TextToSpeech(this@MainActivity, initListener, enginePackage)
                }

                continuation.invokeOnCancellation {
                    ttsRef.shutdown()
                }
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private suspend fun runTtsSpeakProbe(tts: TextToSpeech, sampleText: String): Pair<Boolean, String> {
        val utteranceId = "probe-${SystemClock.elapsedRealtime()}"

        return suspendCancellableCoroutine { continuation ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceIdFromEngine: String?) = Unit

                override fun onDone(utteranceIdFromEngine: String?) {
                    if (utteranceIdFromEngine == utteranceId && continuation.isActive) {
                        continuation.resume(true to "onDone callback received")
                    }
                }

                override fun onError(utteranceIdFromEngine: String?) {
                    if (utteranceIdFromEngine == utteranceId && continuation.isActive) {
                        continuation.resume(false to "onError callback received")
                    }
                }

                override fun onError(utteranceIdFromEngine: String?, errorCode: Int) {
                    if (utteranceIdFromEngine == utteranceId && continuation.isActive) {
                        continuation.resume(false to "onError callback received ($errorCode)")
                    }
                }
            })

            val speakResult = tts.speak(sampleText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (speakResult == TextToSpeech.ERROR) {
                continuation.resume(false to "speak() returned ERROR")
                return@suspendCancellableCoroutine
            }

            appScope.launch {
                delay(7000)
                if (continuation.isActive) {
                    continuation.resume(false to "No completion callback within 7 seconds")
                }
            }
        }
    }

    private fun showFarsiDiagnosticsDialog(report: String) {
        AlertDialog.Builder(this)
            .setTitle("Farsi TTS diagnostics")
            .setMessage(report)
            .setPositiveButton("OK", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboardManager = getSystemService(ClipboardManager::class.java)
                if (clipboardManager != null) {
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("Farsi TTS diagnostics", report))
                    setStatus("Copied Farsi diagnostics to clipboard.")
                }
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun installedPackageVersion(packageName: String): String? {
        return runCatching {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: packageInfo.longVersionCode.toString()
        }.getOrNull()
    }

    private fun currentDefaultTtsEngine(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH).orEmpty()
    }

    private fun refreshTextToSpeechInstance() {
        namedEngineTts.values.forEach { tts ->
            tts.stop()
            tts.shutdown()
        }
        namedEngineTts.clear()

        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
        initTextToSpeech()
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) {
                setStatus("Text-to-speech initialization failed.", isError = true)
                return@TextToSpeech
            }
            checkVoiceSupport(manual = false)
        }
    }

    private fun maybeInstallAssetsOnFirstRun() {
        val alreadyAttempted = prefs.getBoolean("assets_attempted_once", false)
        if (alreadyAttempted) {
            return
        }

        prefs.edit().putBoolean("assets_attempted_once", true).apply()
        installMissingAssets(manual = false)
    }

    private fun installMissingAssets(manual: Boolean) {
        if (isInstallingAssets) {
            if (manual) {
                setStatus("Asset installation is already running.")
            }
            return
        }

        isInstallingAssets = true
        binding.btnInstallAssets.isEnabled = false
        binding.btnCheckAssets.isEnabled = false

        appScope.launch {
            val failures = mutableListOf<String>()

            for ((index, pair) in requiredPairs.withIndex()) {
                val (source, target) = pair
                setStatus("Installing assets ${index + 1}/${requiredPairs.size}: ${source}->${target}")
                runCatching {
                    ensurePairModel(source, target)
                }.onFailure { error ->
                    failures.add("${source}->${target}: ${error.message}")
                }
            }

            binding.btnInstallAssets.isEnabled = true
            if (!isCheckingAssets) {
                binding.btnCheckAssets.isEnabled = true
            }
            isInstallingAssets = false

            if (failures.isEmpty()) {
                prefs.edit().putBoolean("assets_ready", true).apply()
                setStatus("All offline translation assets are installed.")
            } else {
                prefs.edit().putBoolean("assets_ready", false).apply()
                val summary = failures.firstOrNull() ?: "unknown issue"
                setStatus("Asset install partial: $summary", isError = true)
            }

            checkDownloadedAssets(manual = false)
        }
    }

    private fun checkDownloadedAssets(manual: Boolean) {
        if (isCheckingAssets) {
            if (manual) {
                setStatus("Download check is already running.")
            }
            return
        }

        isCheckingAssets = true
        binding.btnCheckAssets.isEnabled = false

        appScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    remoteModelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
                }
            }

            result.onSuccess { models ->
                val installedCodes = models.map { normalizeCode(it.language) }.toSet()
                val missingCodes = requiredModelCodes - installedCodes
                val installedCount = requiredModelCodes.size - missingCodes.size

                val summary = if (missingCodes.isEmpty()) {
                    "Translation models installed: $installedCount/${requiredModelCodes.size}. System speech packs are managed by Android."
                } else {
                    val missingLabels = missingCodes.map(::labelForCode).sorted().joinToString(", ")
                    "Translation models installed: $installedCount/${requiredModelCodes.size}. Missing: $missingLabels."
                }

                downloadsSummaryText = summary
                updateAssetsSummary()

                if (manual) {
                    setStatus("Download check complete.")
                }
            }.onFailure { error ->
                downloadsSummaryText = "Unable to read downloaded models: ${error.message}"
                updateAssetsSummary()
                if (manual) {
                    setStatus("Download check failed: ${error.message}", isError = true)
                }
            }

            isCheckingAssets = false
            binding.btnCheckAssets.isEnabled = true
        }
    }

    private suspend fun ensurePairModel(sourceCode: String, targetCode: String) {
        withContext(Dispatchers.IO) {
            val translator = getTranslator(sourceCode, targetCode)
            translator.downloadModelIfNeeded(modelDownloadConditions).await()
        }
    }

    private suspend fun translateText(sourceText: String, sourceCode: String, targetCode: String): Pair<String, List<String>> {
        if (sourceCode == targetCode) {
            return sourceText to emptyList()
        }

        if (sourceCode == "en" || targetCode == "en") {
            val translated = translateDirect(sourceText, sourceCode, targetCode)
            return translated to listOf("$sourceCode->$targetCode")
        }

        // Prefer direct translation for non-English pairs, but fall back to English pivot when direct fails.
        val directAttempt = runCatching { translateDirect(sourceText, sourceCode, targetCode) }
        if (directAttempt.isSuccess) {
            return directAttempt.getOrThrow() to listOf("$sourceCode->$targetCode")
        }

        val englishText = translateDirect(sourceText, sourceCode, "en")
        val targetText = translateDirect(englishText, "en", targetCode)
        return targetText to listOf("$sourceCode->en", "en->$targetCode")
    }

    private suspend fun translateDirect(sourceText: String, sourceCode: String, targetCode: String): String {
        return withContext(Dispatchers.IO) {
            val translator = getTranslator(sourceCode, targetCode)
            translator.downloadModelIfNeeded(modelDownloadConditions).await()
            translator.translate(sourceText).await()
        }
    }

    private fun getTranslator(sourceCode: String, targetCode: String): Translator {
        val key = "$sourceCode->$targetCode"
        translatorCache[key]?.let { return it }

        val sourceMl = TranslateLanguage.fromLanguageTag(sourceCode)
            ?: error("Translation language not supported: $sourceCode")
        val targetMl = TranslateLanguage.fromLanguageTag(targetCode)
            ?: error("Translation language not supported: $targetCode")

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceMl)
            .setTargetLanguage(targetMl)
            .build()

        val translator = Translation.getClient(options)
        translatorCache[key] = translator
        return translator
    }

    private suspend fun detectSourceLanguage(transcript: String): String {
        val idResult = runCatching {
            languageIdentifier.identifyLanguage(transcript).await()
        }.getOrDefault("und")

        val normalized = normalizeCode(idResult)
        if (normalized in supportedCodes) {
            return normalized
        }

        if (containsArabicScript(transcript)) {
            return if (looksFarsi(transcript)) "fa" else "ar"
        }

        if (containsCyrillic(transcript)) {
            return if (looksUkrainian(transcript)) "uk" else "ru"
        }

        val lowered = transcript.lowercase(Locale.US)
        return when {
            looksSpanish(lowered) -> "es"
            looksFrench(lowered) -> "fr"
            else -> "en"
        }
    }

    private fun buildTransliteration(targetText: String, targetCode: String, sourceCode: String): TransliterationResult {
        if (targetCode !in transliterationTargets) {
            return TransliterationResult(false, "", "", "", "")
        }

        val latin = TransliterationEngine.arabicScriptToLatin(targetText)
        if (latin.isBlank()) {
            return TransliterationResult(false, "", "", "", "")
        }

        val scriptCode = if (sourceCode in supportedCodes) sourceCode else "en"
        val scriptText = TransliterationEngine.latinToScript(latin, scriptCode)

        return TransliterationResult(
            available = scriptText.isNotBlank(),
            scriptCode = scriptCode,
            scriptLabel = labelForCode(scriptCode),
            scriptText = scriptText,
            latinText = latin,
        )
    }

    private fun appendOutput(
        sourceCode: String,
        sourceText: String,
        targetCode: String,
        targetText: String,
        transliteration: TransliterationResult,
    ) {
        val view = layoutInflater.inflate(R.layout.item_entry, binding.outputContainer, false)

        val sourceLine = view.findViewById<TextView>(R.id.sourceText)
        val targetLine = view.findViewById<TextView>(R.id.targetText)
        val translitLine = view.findViewById<TextView>(R.id.translitText)
        val timeLine = view.findViewById<TextView>(R.id.entryTimeText)
        val speakButton = view.findViewById<Button>(R.id.btnSpeakEntry)

        val sourceLabel = labelForCode(sourceCode)
        val targetLabel = labelForCode(targetCode)

        targetLine.text = "$targetLabel: $targetText"
        sourceLine.text = "$sourceLabel: $sourceText"
        timeLine.text = LocalTime.now().withNano(0).toString()

        applyDirection(targetLine, targetCode)
        applyDirection(sourceLine, sourceCode)

        if (transliteration.available) {
            translitLine.isVisible = true
            translitLine.text = "Pronunciation (${transliteration.scriptLabel} script): ${transliteration.scriptText}"
            applyDirection(translitLine, transliteration.scriptCode)
        } else {
            translitLine.isVisible = false
        }

        speakButton.setOnClickListener {
            speakTarget(targetText, targetCode)
        }

        applyOutputSizeToEntryView(view)

        binding.outputContainer.addView(view, 0)

        entries.add(
            0,
            EntryRecord(
                timestamp = LocalTime.now().withNano(0).toString(),
                sourceLabel = sourceLabel,
                sourceText = sourceText,
                targetLabel = targetLabel,
                targetText = targetText,
                transliterationLabel = transliteration.scriptLabel,
                transliterationText = if (transliteration.available) transliteration.scriptText else "",
            )
        )
    }

    private fun applyDirection(textView: TextView, code: String) {
        val rtl = code == "fa" || code == "ar"
        textView.textDirection = if (rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        textView.gravity = if (rtl) Gravity.END else Gravity.START
    }

    private fun checkVoiceSupport(manual: Boolean) {
        if (isCheckingVoices) {
            if (manual) {
                setStatus("Voice check is already running.")
            }
            return
        }

        isCheckingVoices = true
        binding.btnCheckVoices.isEnabled = false

        appScope.launch {
            val missingLabels = mutableListOf<String>()
            languageOptions.forEach { option ->
                val route = resolveRouteForOutputCode(option.code)
                val supported = route != null && isVoiceAvailable(route.tts, option.code)
                availableTtsByCode[option.code] = supported
                if (!supported) {
                    missingLabels.add(option.label)
                }
            }

            val message = if (missingLabels.isEmpty()) {
                "Voices available for all app languages (auto-routed by language)."
            } else {
                "Missing voices: ${missingLabels.joinToString(", ")}. Tap Voice Settings or Diagnose Farsi TTS."
            }

            voicesSummaryText = message
            updateAssetsSummary()
            if (manual) {
                setStatus("Voice check complete.")
            }

            isCheckingVoices = false
            binding.btnCheckVoices.isEnabled = true
        }
    }

    private fun localeCandidatesForCode(code: String): List<Locale> {
        val specific = Locale.forLanguageTag(localeTagForCode(code))
        val generic = Locale.forLanguageTag(normalizeCode(code))
        return listOf(specific, generic).distinctBy { it.toLanguageTag() }
    }

    private fun configureVoiceForCode(tts: TextToSpeech, code: String): Boolean {
        val normalized = normalizeCode(code)
        val matchingVoice = tts.voices
            ?.filter { normalizeCode(it.locale.toLanguageTag()) == normalized }
            ?.sortedBy { if (it.isNetworkConnectionRequired) 1 else 0 }
            ?.firstOrNull()

        if (matchingVoice != null) {
            tts.voice = matchingVoice
            return true
        }

        return localeCandidatesForCode(normalized)
            .any { locale -> isLanguageResultSupported(tts.setLanguage(locale)) }
    }

    private fun isVoiceAvailable(tts: TextToSpeech, code: String): Boolean {
        val normalized = normalizeCode(code)
        val localeAvailable = localeCandidatesForCode(normalized)
            .any { locale -> isLanguageResultSupported(tts.isLanguageAvailable(locale)) }

        if (localeAvailable) {
            return true
        }

        val voices = tts.voices ?: emptySet()
        return voices.any { voice ->
            normalizeCode(voice.locale.toLanguageTag()) == normalized
        }
    }

    private fun openVoiceSettings() {
        val intents = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        )

        for (intent in intents) {
            runCatching { startActivity(intent) }.onSuccess {
                setStatus("Opened voice settings.")
                return
            }.onFailure { error ->
                if (error !is ActivityNotFoundException) {
                    setStatus("Unable to open voice settings: ${error.message}", isError = true)
                    return
                }
            }
        }

        setStatus("No voice settings activity found on this device.", isError = true)
    }

    private fun speakTarget(text: String, targetCode: String) {
        appScope.launch {
            val code = normalizeCode(targetCode)
            val route = resolveRouteForOutputCode(code)
            if (route == null) {
                availableTtsByCode[code] = false
                setStatus("No text-to-speech engine is available for ${labelForCode(code)}.", isError = true)
                return@launch
            }

            val tts = route.tts
            val localeReady = configureVoiceForCode(tts, code)
            if (!localeReady) {
                availableTtsByCode[code] = false
                val engineName = engineLabelForPackage(route.enginePackage)
                setStatus("No ${labelForCode(code)} voice available in $engineName.", isError = true)
                return@launch
            }

            availableTtsByCode[code] = true
            tts.setSpeechRate(currentSpeechRate())
            val speakResult = tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "speak-${SystemClock.elapsedRealtime()}"
            )

            if (speakResult == TextToSpeech.ERROR) {
                val engineName = engineLabelForPackage(route.enginePackage)
                setStatus("Speech playback failed in $engineName.", isError = true)
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private suspend fun speakTargetAndWait(text: String, targetCode: String): Boolean {
        val code = normalizeCode(targetCode)
        val route = resolveRouteForOutputCode(code)
        if (route == null) {
            availableTtsByCode[code] = false
            setStatus("No text-to-speech engine is available for ${labelForCode(code)}.", isError = true)
            return false
        }

        val tts = route.tts
        val localeReady = configureVoiceForCode(tts, code)
        if (!localeReady) {
            availableTtsByCode[code] = false
            val engineName = engineLabelForPackage(route.enginePackage)
            setStatus("No ${labelForCode(code)} voice available in $engineName.", isError = true)
            return false
        }

        availableTtsByCode[code] = true
        tts.setSpeechRate(currentSpeechRate())
        val utteranceId = "converse-${SystemClock.elapsedRealtime()}"
        isConverseSpeaking = true

        val completed = withTimeoutOrNull(15000L) {
            suspendCancellableCoroutine { continuation ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceIdFromEngine: String?) {
                        if (utteranceIdFromEngine == utteranceId && isConverseActive) {
                            appScope.launch {
                                updateConverseStatus("Speaking ${labelForCode(code)}...")
                            }
                        }
                    }

                    override fun onDone(utteranceIdFromEngine: String?) {
                        if (utteranceIdFromEngine == utteranceId && continuation.isActive) {
                            continuation.resume(true)
                        }
                    }

                    override fun onError(utteranceIdFromEngine: String?) {
                        if (utteranceIdFromEngine == utteranceId && continuation.isActive) {
                            continuation.resume(false)
                        }
                    }

                    override fun onError(utteranceIdFromEngine: String?, errorCode: Int) {
                        if (utteranceIdFromEngine == utteranceId && continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                })

                val speakResult = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (speakResult == TextToSpeech.ERROR && continuation.isActive) {
                    continuation.resume(false)
                }
            }
        } ?: false

        isConverseSpeaking = false
        if (!completed) {
            runCatching { tts.stop() }
        }
        return completed
    }

    private fun exportTranscript() {
        if (entries.isEmpty()) {
            setStatus("Nothing to export.")
            return
        }

        val content = entries
            .asReversed()
            .joinToString("\n") { entry ->
                val transliterationLine = if (entry.transliterationText.isNotBlank()) {
                    "Pronunciation (${entry.transliterationLabel} script): ${entry.transliterationText}\n"
                } else {
                    ""
                }
                "[${entry.timestamp}] ${entry.targetLabel}: ${entry.targetText}\n" +
                    transliterationLine +
                    "${entry.sourceLabel}: ${entry.sourceText}\n"
            }

        pendingExportText = content
        val fileName = "babeltrout-${LocalDate.now()}.txt"
        exportLauncher.launch(fileName)
    }

    private fun processResults(transcripts: List<String>, sourceHint: String?) {
        val targetCode = selectedTargetCode()

        appScope.launch {
            runCatching {
                val (transcript, sourceCode) = resolveMainTranscriptAndSource(transcripts, sourceHint)
                if (sourceCode !in supportedCodes) {
                    error("Unsupported detected language: $sourceCode")
                }

                val (targetText, route) = translateText(transcript, sourceCode, targetCode)
                val transliteration = buildTransliteration(targetText, targetCode, sourceCode)

                appendOutput(sourceCode, transcript, targetCode, targetText, transliteration)
                speakTarget(targetText, targetCode)

                val sourceInfo = if (sourceHint == null) {
                    "Detected ${labelForCode(sourceCode)}"
                } else {
                    "Used ${labelForCode(sourceCode)}"
                }

                setStatus("$sourceInfo, translated to ${labelForCode(targetCode)} (${route.joinToString(" -> ")})")
            }.onFailure { error ->
                setStatus("Processing failed: ${error.message}", isError = true)
            }

            isProcessing = false
            activeSourceCode = null
        }
    }

    private suspend fun resolveMainTranscriptAndSource(
        transcripts: List<String>,
        sourceHint: String?,
    ): Pair<String, String> {
        val candidates = transcripts.map { it.trim() }.filter { it.isNotBlank() }
        if (candidates.isEmpty()) {
            error("No speech detected.")
        }

        val normalizedHint = normalizeCode(sourceHint)
        if (normalizedHint in supportedCodes) {
            val transcript = pickBestTranscriptForForcedSource(candidates, normalizedHint)
            return transcript to normalizedHint
        }

        return pickBestAutoTranscript(candidates)
    }

    private suspend fun pickBestAutoTranscript(candidates: List<String>): Pair<String, String> {
        var bestTranscript = candidates.first()
        var bestSource = detectSourceLanguage(bestTranscript)
        var bestScore = scoreMainTranscriptCandidate(bestTranscript, bestSource, 0)

        for (index in 1 until candidates.size) {
            val candidate = candidates[index]
            val detectedSource = detectSourceLanguage(candidate)
            val candidateScore = scoreMainTranscriptCandidate(candidate, detectedSource, index)
            if (candidateScore > bestScore) {
                bestTranscript = candidate
                bestSource = detectedSource
                bestScore = candidateScore
            }
        }

        return bestTranscript to bestSource
    }

    private fun pickBestTranscriptForForcedSource(candidates: List<String>, sourceCode: String): String {
        return candidates.maxByOrNull { scoreForcedSourceTranscript(it, sourceCode) } ?: candidates.first()
    }

    private fun scoreMainTranscriptCandidate(transcript: String, sourceCode: String, index: Int): Int {
        val normalizedSource = normalizeCode(sourceCode)
        var score = scoreLanguageForTranscript(transcript, normalizedSource) * 4
        score += when (normalizedSource) {
            "fa", "ar" -> if (containsArabicScript(transcript)) 6 else -2
            "uk", "ru" -> if (containsCyrillic(transcript)) 6 else -2
            else -> if (!containsArabicScript(transcript) && !containsCyrillic(transcript)) 2 else 0
        }
        score += minOf(transcript.count { it.isLetter() } / 6, 4)
        score -= index
        return score
    }

    private fun scoreForcedSourceTranscript(transcript: String, sourceCode: String): Int {
        val normalizedSource = normalizeCode(sourceCode)
        var score = scoreLanguageForTranscript(transcript, normalizedSource) * 5
        score += when (normalizedSource) {
            "fa", "ar" -> if (containsArabicScript(transcript)) 8 else -2
            "uk", "ru" -> if (containsCyrillic(transcript)) 8 else -2
            else -> if (!containsArabicScript(transcript) && !containsCyrillic(transcript)) 2 else -1
        }
        score += minOf(transcript.length / 8, 4)
        return score
    }

    private fun resetCaptureState() {
        activeButton?.let { button ->
            val defaultText = button.tag as? String
            if (defaultText != null) {
                button.text = defaultText
            }
            button.tag = null
        }

        activeButton = null
        activeSourceCode = null
        isListening = false
    }

    private fun selectedTargetCode(): String {
        val index = binding.targetLanguageSpinner.selectedItemPosition
        return languageOptions.getOrNull(index)?.code ?: "uk"
    }

    private fun updateSpeechRateLabel() {
        val label = "Speech rate: ${"%.2f".format(Locale.US, currentSpeechRate())}x"
        binding.speechRateLabel.text = label
        binding.supportSpeechRateLabel.text = label
    }

    private fun currentSpeechRate(): Float {
        return 0.5f + (binding.speechRateSeek.progress * 0.05f)
    }

    private fun updateOutputSizeLabel() {
        val label = "Output text size: ${currentOutputTextSizeSp().toInt()}sp"
        binding.outputSizeLabel.text = label
        binding.supportOutputSizeLabel.text = label
    }

    private fun currentOutputTextSizeSp(): Float {
        return 14f + binding.outputSizeSeek.progress.toFloat()
    }

    private fun applyOutputSizeToAllEntries() {
        for (index in 0 until binding.outputContainer.childCount) {
            applyOutputSizeToEntryView(binding.outputContainer.getChildAt(index))
        }
        for (index in 0 until binding.converseOutputContainer.childCount) {
            applyOutputSizeToConverseEntryView(binding.converseOutputContainer.getChildAt(index))
        }
    }

    private fun applyOutputSizeToEntryView(entryView: View) {
        val target = entryView.findViewById<TextView>(R.id.targetText)
        val translit = entryView.findViewById<TextView>(R.id.translitText)
        val source = entryView.findViewById<TextView>(R.id.sourceText)

        val base = currentOutputTextSizeSp()
        target.setTextSize(TypedValue.COMPLEX_UNIT_SP, base + 2f)
        translit.setTextSize(TypedValue.COMPLEX_UNIT_SP, base)
        source.setTextSize(TypedValue.COMPLEX_UNIT_SP, base - 1f)
    }

    private fun applyOutputSizeToConverseEntryView(entryView: View) {
        val source = entryView.findViewById<TextView>(R.id.converseSourceText)
        val target = entryView.findViewById<TextView>(R.id.converseTargetText)
        val translit = entryView.findViewById<TextView>(R.id.converseTranslitText)

        val base = currentOutputTextSizeSp()
        source.setTextSize(TypedValue.COMPLEX_UNIT_SP, base - 1f)
        target.setTextSize(TypedValue.COMPLEX_UNIT_SP, base + 2f)
        translit.setTextSize(TypedValue.COMPLEX_UNIT_SP, base)
    }

    private fun labelForCode(code: String): String {
        return languageOptions.firstOrNull { it.code == code }?.label ?: code
    }

    private fun localeTagForCode(code: String): String {
        return languageOptions.firstOrNull { it.code == code }?.localeTag ?: "en-US"
    }

    private fun recognitionTagForCode(code: String): String {
        return when (normalizeCode(code)) {
            "en", "fa", "uk", "ru", "ar", "fr", "es" -> normalizeCode(code)
            else -> localeTagForCode(code)
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateAssetsSummary() {
        binding.assetsSummaryText.text = "$downloadsSummaryText\n$voicesSummaryText"
    }

    private fun setStatus(message: String, isError: Boolean = false) {
        binding.statusText.text = message
        binding.statusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (isError) R.color.status_error_text else R.color.status_text
            )
        )
    }

    private fun recognitionErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "audio capture error"
            SpeechRecognizer.ERROR_CLIENT -> "client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "missing mic permission"
            SpeechRecognizer.ERROR_NETWORK -> "network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "no speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
            10 -> "too many requests"
            11 -> "server disconnected"
            12 -> "language not supported by recognizer"
            13 -> "language unavailable (download speech pack or use network)"
            14 -> "cannot check language support"
            15 -> "cannot monitor language download"
            else -> "unknown error ($error)"
        }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        resetCaptureState()
        isProcessing = false
        setStatus("Recognition error: ${recognitionErrorMessage(error)}", isError = true)
    }

    override fun onResults(results: Bundle?) {
        val transcripts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val sourceHint = activeSourceCode

        resetCaptureState()

        if (transcripts.isEmpty()) {
            isProcessing = false
            setStatus("No speech detected.")
            return
        }

        processResults(transcripts, sourceHint)
    }

    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onResume() {
        super.onResume()

        val currentDefaultEngine = currentDefaultTtsEngine()
        if (currentDefaultEngine.isNotBlank() && currentDefaultEngine != lastKnownDefaultTtsEngine) {
            lastKnownDefaultTtsEngine = currentDefaultEngine
            refreshTextToSpeechInstance()
            setStatus("Detected TTS engine change. Reinitialized text-to-speech.")
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this) {
            if (currentPage != UiPage.MAIN) {
                showPage(UiPage.MAIN)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        stopConverseMode("")
        speechRecognizer.destroy()
        converseSpeechRecognizer.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        namedEngineTts.values.forEach { tts ->
            tts.stop()
            tts.shutdown()
        }
        namedEngineTts.clear()
        languageIdentifier.close()
        translatorCache.values.forEach { it.close() }
        appScope.cancel()
    }

    private fun normalizeCode(code: String?): String {
        if (code.isNullOrBlank()) {
            return ""
        }
        return code.lowercase(Locale.US).substringBefore('-')
    }

    private fun containsArabicScript(text: String): Boolean = text.any { it in '\u0600'..'\u06FF' }

    private fun containsCyrillic(text: String): Boolean = text.any { it in '\u0400'..'\u04FF' }

    private fun looksFarsi(text: String): Boolean {
        val markers = listOf('گ', 'چ', 'پ', 'ژ', 'ی')
        return text.any { it in markers }
    }

    private fun looksUkrainian(text: String): Boolean {
        val markers = listOf('і', 'ї', 'є', 'ґ', 'І', 'Ї', 'Є', 'Ґ')
        return text.any { it in markers }
    }

    private fun looksSpanish(text: String): Boolean {
        return listOf(" el ", " la ", " de ", " que ", " y ", " por ", " para ", " con ")
            .any { text.contains(it) } || text.any { it in listOf('ñ', 'á', 'é', 'í', 'ó', 'ú', '¿', '¡') }
    }

    private fun looksFrench(text: String): Boolean {
        return listOf(" le ", " la ", " les ", " des ", " est ", " pour ", " avec ", " une ")
            .any { text.contains(it) } || text.any { it in listOf('à', 'â', 'ç', 'é', 'è', 'ê', 'ë', 'î', 'ï', 'ô', 'ù', 'û', 'ü') }
    }
}

private object TransliterationEngine {

    private val arabicToLatin = mapOf(
        "ا" to "a",
        "آ" to "aa",
        "أ" to "a",
        "إ" to "e",
        "ٱ" to "a",
        "ء" to "",
        "ؤ" to "u",
        "ئ" to "y",
        "ب" to "b",
        "پ" to "p",
        "ت" to "t",
        "ث" to "s",
        "ج" to "j",
        "چ" to "ch",
        "ح" to "h",
        "خ" to "kh",
        "د" to "d",
        "ذ" to "z",
        "ر" to "r",
        "ز" to "z",
        "ژ" to "zh",
        "س" to "s",
        "ش" to "sh",
        "ص" to "s",
        "ض" to "z",
        "ط" to "t",
        "ظ" to "z",
        "ع" to "a",
        "غ" to "gh",
        "ف" to "f",
        "ق" to "q",
        "ك" to "k",
        "ک" to "k",
        "گ" to "g",
        "ل" to "l",
        "م" to "m",
        "ن" to "n",
        "ه" to "h",
        "ة" to "a",
        "و" to "u",
        "ي" to "y",
        "ی" to "y",
        "ى" to "a",
        "َ" to "a",
        "ِ" to "i",
        "ُ" to "u",
        "ً" to "an",
        "ٍ" to "in",
        "ٌ" to "un",
        "ْ" to "",
        "ّ" to "",
        "\u200c" to "",
    )

    private val ukDigraphs = listOf(
        "shch" to "щ",
        "zh" to "ж",
        "kh" to "х",
        "ch" to "ч",
        "sh" to "ш",
        "ts" to "ц",
        "ya" to "я",
        "yu" to "ю",
        "yo" to "йо",
        "ye" to "є",
        "yi" to "ї",
        "ia" to "я",
        "iu" to "ю",
        "ie" to "є",
    )

    private val ukSingle = mapOf(
        'a' to "а",
        'b' to "б",
        'c' to "к",
        'd' to "д",
        'e' to "е",
        'f' to "ф",
        'g' to "ґ",
        'h' to "г",
        'i' to "і",
        'j' to "дж",
        'k' to "к",
        'l' to "л",
        'm' to "м",
        'n' to "н",
        'o' to "о",
        'p' to "п",
        'q' to "к",
        'r' to "р",
        's' to "с",
        't' to "т",
        'u' to "у",
        'v' to "в",
        'w' to "в",
        'x' to "кс",
        'y' to "й",
        'z' to "з",
    )

    private val ruDigraphs = listOf(
        "shch" to "щ",
        "zh" to "ж",
        "kh" to "х",
        "ch" to "ч",
        "sh" to "ш",
        "ts" to "ц",
        "ya" to "я",
        "yu" to "ю",
        "yo" to "ё",
        "ye" to "е",
        "yi" to "и",
        "ia" to "я",
        "iu" to "ю",
        "ie" to "е",
    )

    private val ruSingle = mapOf(
        'a' to "а",
        'b' to "б",
        'c' to "к",
        'd' to "д",
        'e' to "е",
        'f' to "ф",
        'g' to "г",
        'h' to "х",
        'i' to "и",
        'j' to "дж",
        'k' to "к",
        'l' to "л",
        'm' to "м",
        'n' to "н",
        'o' to "о",
        'p' to "п",
        'q' to "к",
        'r' to "р",
        's' to "с",
        't' to "т",
        'u' to "у",
        'v' to "в",
        'w' to "в",
        'x' to "кс",
        'y' to "й",
        'z' to "з",
    )

    private val ukCyrillicToLatin = mapOf(
        'а' to "a",
        'б' to "b",
        'в' to "v",
        'г' to "h",
        'ґ' to "g",
        'д' to "d",
        'е' to "e",
        'є' to "ye",
        'ж' to "zh",
        'з' to "z",
        'и' to "y",
        'і' to "i",
        'ї' to "yi",
        'й' to "y",
        'к' to "k",
        'л' to "l",
        'м' to "m",
        'н' to "n",
        'о' to "o",
        'п' to "p",
        'р' to "r",
        'с' to "s",
        'т' to "t",
        'у' to "u",
        'ф' to "f",
        'х' to "kh",
        'ц' to "ts",
        'ч' to "ch",
        'ш' to "sh",
        'щ' to "shch",
        'ь' to "",
        'ю' to "yu",
        'я' to "ya",
    )

    private val ruCyrillicToLatin = mapOf(
        'а' to "a",
        'б' to "b",
        'в' to "v",
        'г' to "g",
        'д' to "d",
        'е' to "e",
        'ё' to "yo",
        'ж' to "zh",
        'з' to "z",
        'и' to "i",
        'й' to "y",
        'к' to "k",
        'л' to "l",
        'м' to "m",
        'н' to "n",
        'о' to "o",
        'п' to "p",
        'р' to "r",
        'с' to "s",
        'т' to "t",
        'у' to "u",
        'ф' to "f",
        'х' to "kh",
        'ц' to "ts",
        'ч' to "ch",
        'ш' to "sh",
        'щ' to "shch",
        'ъ' to "",
        'ы' to "y",
        'ь' to "",
        'э' to "e",
        'ю' to "yu",
        'я' to "ya",
    )

    private val faDigraphs = listOf(
        "sh" to "ش",
        "kh" to "خ",
        "gh" to "غ",
        "ch" to "چ",
        "zh" to "ژ",
        "th" to "ث",
        "dh" to "ذ",
        "aa" to "ا",
        "ee" to "ی",
        "oo" to "و",
        "ou" to "و",
    )

    private val faSingle = mapOf(
        'a' to "ا",
        'b' to "ب",
        'c' to "ک",
        'd' to "د",
        'e' to "ی",
        'f' to "ف",
        'g' to "گ",
        'h' to "ه",
        'i' to "ی",
        'j' to "ج",
        'k' to "ک",
        'l' to "ل",
        'm' to "م",
        'n' to "ن",
        'o' to "و",
        'p' to "پ",
        'q' to "ق",
        'r' to "ر",
        's' to "س",
        't' to "ت",
        'u' to "و",
        'v' to "و",
        'w' to "و",
        'x' to "کس",
        'y' to "ی",
        'z' to "ز",
    )

    private val arDigraphs = listOf(
        "sh" to "ش",
        "kh" to "خ",
        "gh" to "غ",
        "ch" to "تش",
        "zh" to "ج",
        "th" to "ث",
        "dh" to "ذ",
        "aa" to "ا",
        "ee" to "ي",
        "oo" to "و",
        "ou" to "و",
    )

    private val arSingle = mapOf(
        'a' to "ا",
        'b' to "ب",
        'c' to "ك",
        'd' to "د",
        'e' to "ي",
        'f' to "ف",
        'g' to "ج",
        'h' to "ه",
        'i' to "ي",
        'j' to "ج",
        'k' to "ك",
        'l' to "ل",
        'm' to "م",
        'n' to "ن",
        'o' to "و",
        'p' to "ب",
        'q' to "ق",
        'r' to "ر",
        's' to "س",
        't' to "ت",
        'u' to "و",
        'v' to "و",
        'w' to "و",
        'x' to "كس",
        'y' to "ي",
        'z' to "ز",
    )

    fun arabicScriptToLatin(text: String): String {
        val normalized = text.replace("ﻻ", "لا")
        val out = StringBuilder()
        var index = 0
        while (index < normalized.length) {
            if (index + 1 < normalized.length && normalized.substring(index, index + 2) == "لا") {
                out.append("la")
                index += 2
                continue
            }
            val ch = normalized[index].toString()
            out.append(arabicToLatin[ch] ?: ch)
            index += 1
        }
        return collapseSpaces(out.toString())
    }

    fun latinToScript(latinText: String, targetScriptCode: String): String {
        val normalized = collapseSpaces(latinText)
        return when (targetScriptCode) {
            "en", "fr", "es" -> normalized
            "uk" -> transliterateWithMaps(normalized, ukDigraphs, ukSingle)
            "ru" -> transliterateWithMaps(normalized, ruDigraphs, ruSingle)
            "fa" -> transliterateWithMaps(normalized, faDigraphs, faSingle)
            "ar" -> transliterateWithMaps(normalized, arDigraphs, arSingle)
            else -> normalized
        }
    }

    fun cyrillicToLatin(text: String, sourceScriptCode: String): String {
        val mapping = when (sourceScriptCode) {
            "uk" -> ukCyrillicToLatin
            "ru" -> ruCyrillicToLatin
            else -> emptyMap()
        }
        if (mapping.isEmpty()) {
            return collapseSpaces(text)
        }

        val out = StringBuilder()
        text.forEach { ch ->
            val lower = ch.lowercaseChar()
            val mapped = mapping[lower]
            if (mapped == null) {
                out.append(ch)
            } else if (ch.isUpperCase() && mapped.isNotBlank()) {
                out.append(mapped.replaceFirstChar { it.uppercase(Locale.US) })
            } else {
                out.append(mapped)
            }
        }
        return collapseSpaces(out.toString())
    }

    private fun transliterateWithMaps(
        text: String,
        digraphs: List<Pair<String, String>>,
        single: Map<Char, String>,
    ): String {
        val lower = text.lowercase(Locale.US)
        val out = StringBuilder()
        var index = 0

        while (index < text.length) {
            var matched = false

            for ((pattern, replacement) in digraphs) {
                if (lower.startsWith(pattern, index)) {
                    out.append(replacement)
                    index += pattern.length
                    matched = true
                    break
                }
            }

            if (matched) {
                continue
            }

            val key = lower[index]
            out.append(single[key] ?: text[index])
            index += 1
        }

        return collapseSpaces(out.toString())
    }

    private fun collapseSpaces(text: String): String {
        return text.trim().replace(Regex("\\s+"), " ")
    }
}
