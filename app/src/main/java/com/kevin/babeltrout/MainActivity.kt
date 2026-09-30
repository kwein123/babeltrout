package com.kevin.babeltrout

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.util.TypedValue
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
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
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : AppCompatActivity(), RecognitionListener {

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
        /** Voice the user pinned in "Choose Voices", or null for automatic selection. */
        val pinnedVoiceName: String? = null,
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

    private val languageOptions = Languages.all

    private val supportedCodes = Languages.codes
    private val requiredModelCodes = Languages.codes

    private val modelDownloadConditions = DownloadConditions.Builder().build()

    /** Translator pairs whose models are confirmed on-device, so we skip the per-call download check. */
    private val readyTranslatorPairs = mutableSetOf<String>()

    private val pairSourceResolver = PairSourceResolver()

    /** Built-in Piper voices (sherpa-onnx), stored in app-private storage. */
    private val piperStore by lazy { PiperVoiceStore(File(filesDir, "piper")) }
    private val piperSpeaker by lazy { PiperSpeaker(piperStore) }
    private var isDownloadingVoice = false

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
    /** Latest partial transcript, used to salvage speech when the recognizer errors or times out. */
    private var converseLastPartial = ""
    /** Segments collected during an Android 13+ segmented session, joined when the session ends. */
    private val converseSegments = mutableListOf<String>()

    // Hands-free conversation (Android 13+): the app owns the mic; a VAD finds turns and each turn's
    // audio is streamed into the recognizer. See docs/ARCHITECTURE.md "Conversation mode".
    private var voiceMic: VoiceActivityMic? = null
    private var dualRecognizer: DualTurnRecognizer? = null
    /** Conversation languages, cached for the mic thread (spinners are main-thread only). */
    @Volatile private var handsFreeCodes: List<String> = emptyList()
    private val handsFreePartials = linkedMapOf<String, String>()
    private var handsFreeLastSource: String? = null
    /** Mode of the conversation currently running (fixed when it starts). */
    private var converseUsesHandsFree = false
    /** The recognizer has returned text from app-supplied audio at least once, so the mode works. */
    private var handsFreeVerified = false
    /** Consecutive hands-free turns with no text, before any success; used to detect lack of support. */
    private var handsFreeEmptyTurns = 0
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


            // Long utterances often end in a timeout or client error. Don't throw away what was heard.
            val salvaged = takeBufferedConverseSpeech(includePartial = true)
            if (salvaged.isNotBlank()) {
                handleConverseTranscript(salvaged, "", SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN)
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
            // A turn already handed off (e.g. by onEndOfSegmentedSession) must not be processed twice.
            if (!isConverseActive || isConverseProcessing) {
                return
            }

            isConverseListening = false
            updateConverseMicIndicator()
            val best = ScriptHeuristics.pickBestTranscriptForPair(
                extractTranscripts(results),
                selectedConverseCodeA(),
                selectedConverseCodeB(),
            )
            val buffered = takeBufferedConverseSpeech(includePartial = false)
            val transcript = listOf(buffered, best).filter { it.isNotBlank() }.joinToString(" ")
            val detectedLanguageHint = normalizeCode(results?.getString(SpeechRecognizer.DETECTED_LANGUAGE))
            val detectedLanguageConfidence = results?.getInt(
                SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL,
                SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN
            ) ?: SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN

            if (transcript.isBlank()) {
                handleConverseIdleNoSpeech()
                return
            }

            handleConverseTranscript(transcript, detectedLanguageHint, detectedLanguageConfidence)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isConverseActive) {
                return
            }
            val partial = extractTranscripts(partialResults).firstOrNull() ?: return
            converseLastPartial = partial
            updateConverseStatus("Hearing: ${(converseSegments + partial).joinToString(" ")}")
        }

        // Android 13+ segmented sessions: the recognizer keeps the mic open across pauses and
        // delivers each chunk here, instead of ending the whole session at the first pause.
        override fun onSegmentResults(segmentResults: Bundle) {
            if (!isConverseActive) {
                return
            }
            val segment = ScriptHeuristics.pickBestTranscriptForPair(
                extractTranscripts(segmentResults),
                selectedConverseCodeA(),
                selectedConverseCodeB(),
            )
            if (segment.isNotBlank()) {
                converseSegments += segment
            }
            converseLastPartial = ""
            updateConverseStatus("Hearing: ${converseSegments.joinToString(" ")}")
        }

        override fun onEndOfSegmentedSession() {
            if (!isConverseActive || isConverseProcessing) {
                return
            }
            isConverseListening = false
            updateConverseMicIndicator()
            val transcript = takeBufferedConverseSpeech(includePartial = true)
            if (transcript.isBlank()) {
                handleConverseIdleNoSpeech()
                return
            }
            handleConverseTranscript(transcript, "", SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun extractTranscripts(results: Bundle?): List<String> =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun takeBufferedConverseSpeech(includePartial: Boolean): String {
        val pieces = converseSegments.toMutableList()
        if (includePartial && converseLastPartial.isNotBlank()) {
            pieces += converseLastPartial
        }
        converseSegments.clear()
        converseLastPartial = ""
        return pieces.joinToString(" ").trim()
    }

    private fun handleConverseTranscript(transcript: String, detectedLanguageHint: String, detectedLanguageConfidence: Int) {
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
        bindLanguageSpinner(binding.targetLanguageSpinner, PREF_TARGET_CODE, "uk")
    }

    /** Fills [spinner] with the app languages and remembers the choice across launches. */
    private fun bindLanguageSpinner(spinner: Spinner, prefKey: String, defaultCode: String) {
        val labels = languageOptions.map { it.label }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val saved = prefs.getString(prefKey, defaultCode) ?: defaultCode
        val index = languageOptions.indexOfFirst { it.code == saved }
            .takeIf { it >= 0 }
            ?: languageOptions.indexOfFirst { it.code == defaultCode }.coerceAtLeast(0)
        spinner.setSelection(index)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                languageOptions.getOrNull(position)?.let { prefs.edit().putString(prefKey, it.code).apply() }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupConversePage() {
        bindLanguageSpinner(binding.converseLangASpinner, PREF_CONVERSE_A, "en")
        bindLanguageSpinner(binding.converseLangBSpinner, PREF_CONVERSE_B, "fa")

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.switchConverseHandsFree.isChecked = prefs.getBoolean(PREF_CONVERSE_HANDS_FREE, true)
            binding.switchConverseHandsFree.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(PREF_CONVERSE_HANDS_FREE, isChecked).apply()
                updateClassicConverseSwitches()
                setStatus(
                    if (isChecked) "Hands-free mic on (takes effect next time you start the conversation mic)."
                    else "Hands-free mic off: using the classic recognizer mic."
                )
            }
            updateClassicConverseSwitches()
            binding.switchConverseSegmented.isChecked = prefs.getBoolean(PREF_CONVERSE_SEGMENTED, true)
            binding.switchConverseSegmented.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(PREF_CONVERSE_SEGMENTED, isChecked).apply()
                setStatus(if (isChecked) "Long-speech mode on (applies to the next turn)." else "Long-speech mode off.")
            }
        } else {
            binding.switchConverseHandsFree.isVisible = false
            binding.switchConverseSegmented.isVisible = false
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
        binding.speechRateSeek.progress = prefs.getInt(PREF_SPEECH_RATE_PROGRESS, binding.speechRateSeek.progress)
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
                prefs.edit().putInt(PREF_SPEECH_RATE_PROGRESS, progress).apply()
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
        binding.outputSizeSeek.progress = prefs.getInt(PREF_OUTPUT_SIZE_PROGRESS, binding.outputSizeSeek.progress)
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
                prefs.edit().putInt(PREF_OUTPUT_SIZE_PROGRESS, progress).apply()
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
        bindHoldButton(binding.btnHoldHi, "hi")
        bindHoldButton(binding.btnHoldDe, "de")
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
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        intent.putExtra("android.speech.extra.DICTATION_MODE", true)
        if (useSegmentedConverseSession()) {
            // Keep one session open across natural pauses; results arrive per segment and the
            // session ends only after a longer silence (end of the speaker's turn).
            intent.putExtra(
                RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
            )
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        } else {
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
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

    /**
     * Hands-free: one recognizer per conversation language, locked to it (no auto-detect), reading our
     * turn audio instead of opening the mic. The session lasts until the pipe closes (VAD: turn over).
     */
    private fun buildHandsFreeIntent(code: String, audio: ParcelFileDescriptor): Intent {
        val tag = localeTagForCode(code)
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOfflineRecognition)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VoiceActivityMic.SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
    }

    /** Long-speech and idle auto-restart only apply to the classic recognizer mic. */
    private fun updateClassicConverseSwitches() {
        val classic = !binding.switchConverseHandsFree.isChecked
        binding.switchConverseSegmented.isVisible = classic
        binding.switchConverseAutoRestart.isVisible = classic
    }

    private fun useHandsFreeConversation(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            prefs.getBoolean(PREF_CONVERSE_HANDS_FREE, true)

    private fun useSegmentedConverseSession(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            prefs.getBoolean(PREF_CONVERSE_SEGMENTED, true)

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
        converseSegments.clear()
        converseLastPartial = ""
        pairSourceResolver.reset()
        updateConverseToggleUi()
        updateConverseStatus("Conversation mic is starting...")
        setStatus("Conversation mode started: ${labelForCode(codeA)} <-> ${labelForCode(codeB)}")
        converseUsesHandsFree = useHandsFreeConversation()
        if (converseUsesHandsFree) {
            startHandsFreeMic()
        } else {
            startConverseListening()
        }
    }

    // ---- Hands-free conversation ----------------------------------------------------------------

    @android.annotation.SuppressLint("MissingPermission") // checked in startConverseMode
    private fun startHandsFreeMic() {
        handsFreeEmptyTurns = 0
        handsFreeLastSource = null
        handsFreeCodes = listOf(selectedConverseCodeA(), selectedConverseCodeB())
        dualRecognizer = DualTurnRecognizer(
            context = this,
            buildIntent = ::buildHandsFreeIntent,
            onPartialText = { code, text ->
                handsFreePartials[code] = text
                updateConverseStatus("Hearing: " + handsFreePartials.entries.joinToString("  |  ") { "${labelForCode(it.key)}: ${it.value}" })
            },
            onTurnRecognized = ::onHandsFreeTurnRecognized,
        )
        val mic = VoiceActivityMic(
            assets = assets,
            onEvent = ::onHandsFreeEvent,
            onError = { error ->
                runOnUiThread {
                    if (isConverseActive && converseUsesHandsFree) {
                        fallBackToClassicConversation("Hands-free mic failed (${error.message}).")
                    }
                }
            },
        )
        voiceMic = mic
        mic.start()
        updateConverseMicIndicator()
        updateConverseStatus("Listening hands-free. Just talk; each pause ends a turn.")
    }

    /** Runs on the mic thread for every frame event; only turn boundaries hop to the main thread. */
    private fun onHandsFreeEvent(event: TurnDetector.Event) {
        val recognizer = dualRecognizer ?: return
        when (event) {
            is TurnDetector.Event.TurnStarted -> {
                recognizer.beginTurnAudio(handsFreeCodes, event.preRoll)
                runOnUiThread { startHandsFreeTurn() }
            }
            is TurnDetector.Event.Audio -> recognizer.write(event.samples)
            is TurnDetector.Event.TurnEnded -> {
                // Stop listening until this turn is translated and spoken, so we never hear ourselves.
                voiceMic?.muted = true
                recognizer.finishAudio()
                runOnUiThread {
                    if (isConverseActive) {
                        updateConverseMicIndicator()
                        updateConverseStatus("Recognizing...")
                    }
                }
            }
        }
    }

    private fun startHandsFreeTurn() {
        if (!isConverseActive || !converseUsesHandsFree) {
            dualRecognizer?.cancel()
            return
        }
        handsFreePartials.clear()
        updateConverseStatus("Hearing you...")
        dualRecognizer?.startSessions()
    }

    private fun onHandsFreeTurnRecognized(candidates: List<TurnLanguageChooser.Candidate>, errors: List<Int>) {
        if (!isConverseActive || !converseUsesHandsFree) return

        if (candidates.all { it.text.isBlank() }) {
            val unavailable = errors.any { it == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || it == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED }
            if (unavailable) {
                setStatus("A conversation language isn't available to the speech recognizer. Check Settings → Google → Voice, or stay online.", isError = true)
            }
            val refused = errors.isNotEmpty() && errors.all {
                it != SpeechRecognizer.ERROR_NETWORK && it != SpeechRecognizer.ERROR_NETWORK_TIMEOUT && it != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            }
            if (!handsFreeVerified && refused && !unavailable) {
                fallBackToClassicConversation("Hands-free isn't supported by this phone's recognizer (${recognitionErrorMessage(errors.first())}).")
                return
            }
            handleConverseIdleNoSpeech()
            return
        }

        handsFreeVerified = true
        handsFreeEmptyTurns = 0
        isConverseProcessing = true
        updateConverseStatus("Processing speech...")
        appScope.launch {
            runCatching {
                val scored = candidates.map { it.copy(textLanguageScore = textLanguageScore(it.text, it.languageCode)) }
                val choice = TurnLanguageChooser.choose(scored, handsFreeLastSource) ?: error("No speech recognized.")
                handsFreeLastSource = choice.candidate.languageCode
                android.util.Log.i(
                    "Babeltrout",
                    "turn language: ${choice.candidate.languageCode} by ${choice.reason}; " +
                        scored.joinToString { "${it.languageCode} conf=${it.confidence} text=${it.textLanguageScore}" },
                )
                processConverseTranscript(
                    transcript = choice.candidate.text,
                    detectedLanguageHint = "",
                    detectedLanguageConfidence = SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN,
                    knownSourceCode = choice.candidate.languageCode,
                    decidedBy = choice.reason,
                )
            }.onFailure { error ->
                updateConverseStatus("Conversation processing failed: ${error.message}")
            }
            isConverseProcessing = false
            if (isConverseActive) startConverseListening()
        }
    }

    /** ML Kit's confidence that [text] is written in [code] (0 if it thinks it's something else). */
    private suspend fun textLanguageScore(text: String, code: String): Float? {
        if (text.isBlank()) return null
        return runCatching {
            languageIdentifier.identifyPossibleLanguages(text).await()
                .filter { normalizeCode(it.languageTag) == code }
                .maxOfOrNull { it.confidence } ?: 0f
        }.getOrNull()
    }

    private fun resumeHandsFreeMic() {
        if (!isConverseActive) return
        handsFreeCodes = listOf(selectedConverseCodeA(), selectedConverseCodeB())
        voiceMic?.muted = false
        updateConverseMicIndicator()
    }

    private fun stopHandsFreeMic() {
        voiceMic?.stop()
        voiceMic = null
        dualRecognizer?.destroy()
        dualRecognizer = null
    }

    private fun fallBackToClassicConversation(reason: String) {
        stopHandsFreeMic()
        converseUsesHandsFree = false
        prefs.edit().putBoolean(PREF_CONVERSE_HANDS_FREE, false).apply()
        binding.switchConverseHandsFree.isChecked = false
        setStatus("$reason Switched to classic conversation mode; you can turn hands-free back on to retry.", isError = true)
        runCatching { converseSpeechRecognizer.cancel() }
        isConverseListening = false
        isConverseProcessing = false
        if (isConverseActive) startConverseListening()
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
        converseSegments.clear()
        converseLastPartial = ""
        pairSourceResolver.reset()
        piperSpeaker.stop()
        stopHandsFreeMic()
        runCatching { converseSpeechRecognizer.cancel() }
        updateConverseToggleUi()
        updateConverseStatus("Conversation mic is off.")
        if (message.isNotBlank()) {
            setStatus(message)
        }
    }

    private fun updateConverseToggleUi() {
        binding.btnConverseToggle.text = if (isConverseActive) "Conversation Mic: ON" else "Conversation Mic: OFF"
        // Red while the conversation mic is on, so its state is obvious at a glance.
        binding.btnConverseToggle.backgroundTintList =
            ContextCompat.getColorStateList(this, if (isConverseActive) R.color.mic_live else R.color.primary)
        updateConverseMicIndicator()
    }

    private fun updateConverseMicIndicator() {
        val handsFreeOpen = converseUsesHandsFree && voiceMic?.isRunning == true && voiceMic?.muted == false
        val micIsListening = isConverseActive && (isConverseListening || handsFreeOpen)
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

        if (converseUsesHandsFree) {
            // A cough or door slam can open a turn with no words in it: just keep listening. But if
            // the recognizer has never produced text from our audio, it is probably ignoring it.
            if (!handsFreeVerified && ++handsFreeEmptyTurns >= HANDS_FREE_MAX_EMPTY_TURNS) {
                fallBackToClassicConversation("This phone's recognizer didn't return text from app audio.")
                return
            }
            updateConverseStatus("Didn't catch that. Listening...")
            resumeHandsFreeMic()
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

        if (converseUsesHandsFree) {
            // Hands-free: recognizers run per turn; "listen again" just means reopening our mic.
            resumeHandsFreeMic()
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
        knownSourceCode: String? = null,
        decidedBy: String? = null,
    ) {
        val codeA = selectedConverseCodeA()
        val codeB = selectedConverseCodeB()
        if (codeA == codeB) {
            error("Conversation mode requires two different languages.")
        }

        val sourceCode = knownSourceCode ?: detectSourceWithinPair(
            transcript = transcript,
            codeA = codeA,
            codeB = codeB,
            detectedLanguageHint = detectedLanguageHint,
            detectedLanguageConfidence = detectedLanguageConfidence,
        )
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
        val hintInfo = when {
            decidedBy != null -> " | chosen by $decidedBy"
            detectedLanguageHint == sourceCode -> " | speech engine: ${labelForCode(sourceCode)}"
            else -> ""
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
        val hintConfident = detectedLanguageConfidence >= SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT
        pairSourceResolver.fromRecognizerHint(transcript, codeA, codeB, detectedLanguageHint, hintConfident)
            ?.let { return it }

        val mlDetected = detectSourceLanguage(transcript)
        return pairSourceResolver.resolve(transcript, codeA, codeB, detectedLanguageHint, mlDetected)
    }

    private fun transliterateTextToScript(text: String, textCode: String, targetScriptCode: String): String {
        val latin = TransliterationEngine.toLatin(text, textCode)
        return TransliterationEngine.latinToScript(latin, targetScriptCode).ifBlank { text }
    }

    private fun normalizeConverseSourceText(transcript: String, sourceCode: String): String {
        val normalizedSourceCode = normalizeCode(sourceCode)
        val sourceScript = Languages.script(normalizedSourceCode)
        val hasSourceScript = sourceScript == Script.LATIN || ScriptHeuristics.hasScript(transcript, sourceScript)

        if (hasSourceScript || !transcript.any { it.isLetter() }) {
            return transcript
        }

        // The recognizer sometimes returns a romanized transcript for a non-Latin language.
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
        val sourceScript = Languages.script(normalizedSourceCode)
        val targetScript = Languages.script(normalizedTargetCode)

        // Non-Latin source into a Latin-script target: show how the speaker's words sound, in Latin letters.
        if (sourceScript != Script.LATIN && targetScript == Script.LATIN) {
            val latin = TransliterationEngine.toLatin(sourceText, normalizedSourceCode).ifBlank { sourceText }
            return latin to normalizedTargetCode
        }

        // Same script on both sides (Farsi/Arabic, Ukrainian/Russian): a Latin reading is more useful.
        if (sourceScript == targetScript) {
            return TransliterationEngine.toLatin(targetText, normalizedTargetCode) to "en"
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
        val translitLabel = if (Languages.script(transliteratedCode) == Script.LATIN) "Latin" else labelForCode(transliteratedCode)

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
            if (isOnMeteredNetwork()) {
                AlertDialog.Builder(this)
                    .setTitle("Use mobile data?")
                    .setMessage(
                        "Install Assets downloads translation models, voices and offline speech recognition " +
                            "for every language, which can be several hundred MB, and you're not on Wi-Fi."
                    )
                    .setPositiveButton("Download") { _, _ -> installMissingAssets(manual = true) }
                    .setNegativeButton("Wait for Wi-Fi", null)
                    .show()
            } else {
                installMissingAssets(manual = true)
            }
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

        binding.btnChooseVoices.setOnClickListener {
            showVoiceLanguagePicker()
        }

        binding.btnVoiceLibrary.setOnClickListener {
            showVoiceLibraryLanguagePicker()
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

    /** TTS instance bound to [enginePackage], reusing the default instance when it is the same engine. */
    private suspend fun ttsForEngine(enginePackage: String): TextToSpeech? {
        val defaultTts = if (ttsReady) textToSpeech else null
        if (enginePackage == currentDefaultTtsEngine() && defaultTts != null) {
            return defaultTts
        }
        return getOrCreateNamedEngineTts(enginePackage)
    }

    private fun pinnedVoiceFor(code: String): Pair<String, String>? {
        val stored = prefs.getString(PREF_VOICE_PREFIX + normalizeCode(code), null) ?: return null
        val engine = stored.substringBefore(PINNED_VOICE_SEPARATOR, "")
        val voice = stored.substringAfter(PINNED_VOICE_SEPARATOR, "")
        return if (engine.isBlank() || voice.isBlank()) null else engine to voice
    }

    /**
     * The built-in voice to use for [code], or null to use an Android TTS engine.
     * A pinned built-in voice always wins; a voice pinned in another engine means "not built-in".
     * With nothing pinned, Farsi prefers a built-in voice over SherpaTTS.
     */
    private fun inAppVoiceFor(code: String): PiperVoiceStore.Installed? {
        val normalized = normalizeCode(code)
        val pinned = pinnedVoiceFor(normalized)
        if (pinned != null) {
            return if (pinned.first == IN_APP_ENGINE) piperStore.findByKey(pinned.second) else null
        }
        if (normalized != "fa") return null
        val installed = piperStore.installed(normalized)
        return installed.firstOrNull { it.quality == PiperVoiceCatalog.Quality.FULL } ?: installed.firstOrNull()
    }

    private fun stopAllSpeech() {
        piperSpeaker.stop()
        runCatching { textToSpeech?.stop() }
        namedEngineTts.values.forEach { runCatching { it.stop() } }
    }

    private suspend fun resolveRouteForOutputCode(code: String): TtsEngineRoute? {
        val normalizedCode = normalizeCode(code)

        // 1. A voice the user explicitly chose in "Choose Voices" wins, if it is still installed.
        pinnedVoiceFor(normalizedCode)?.let { (enginePackage, voiceName) ->
            val tts = ttsForEngine(enginePackage)
            if (tts != null && runCatching { tts.voices }.getOrNull().orEmpty().any { it.name == voiceName }) {
                return TtsEngineRoute(tts, enginePackage, voiceName)
            }
        }

        // 2. Automatic routing: Farsi -> SherpaTTS, everything else -> Google TTS, then the default engine.
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

    private data class VoiceChoice(val enginePackage: String, val engineLabel: String, val voice: Voice)

    private fun describeVoice(voice: Voice): String {
        val where = if (voice.isNetworkConnectionRequired) "online" else "offline"
        return "${voice.name} ($where)"
    }

    /** Every installed voice for [code], across all TTS engines on the device. */
    private suspend fun collectVoices(code: String): List<VoiceChoice> {
        val normalized = normalizeCode(code)
        val engines = textToSpeech?.engines.orEmpty()
        val choices = mutableListOf<VoiceChoice>()
        for (engine in engines) {
            val tts = ttsForEngine(engine.name) ?: continue
            runCatching { tts.voices }.getOrNull().orEmpty()
                .filter { normalizeCode(it.locale.toLanguageTag()) == normalized }
                .filterNot { it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
                .forEach { choices += VoiceChoice(engine.name, engine.label.toString(), it) }
        }
        return choices
    }

    /** Human-readable name for a pinned (engine, voice) pair. */
    private fun pinnedLabel(pinned: Pair<String, String>): String =
        if (pinned.first == IN_APP_ENGINE) {
            piperStore.findByKey(pinned.second)?.let { "${it.label}, built-in" } ?: "built-in voice (not installed)"
        } else {
            pinned.second
        }

    private fun pinVoice(code: String, engine: String, voiceName: String) {
        prefs.edit().putString(PREF_VOICE_PREFIX + normalizeCode(code), engine + PINNED_VOICE_SEPARATOR + voiceName).apply()
    }

    private fun showVoiceLanguagePicker() {
        val labels = languageOptions.map { option ->
            val current = pinnedVoiceFor(option.code)?.let { pinnedLabel(it) }
                ?: inAppVoiceFor(option.code)?.let { "automatic (${it.label})" }
                ?: "automatic"
            "${option.label}: $current"
        }
        AlertDialog.Builder(this)
            .setTitle("Choose a voice for...")
            .setItems(labels.toTypedArray()) { _, which -> showVoicesForLanguage(languageOptions[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private sealed class VoiceMenuItem(val label: String) {
        class Automatic(label: String) : VoiceMenuItem(label)
        class BuiltIn(val installed: PiperVoiceStore.Installed) : VoiceMenuItem("Built-in: ${installed.label}")
        class System(val choice: VoiceChoice, label: String) : VoiceMenuItem(label)
        class Library(label: String) : VoiceMenuItem(label)
    }

    private fun showVoicesForLanguage(option: LanguageOption) {
        setStatus("Looking for ${option.label} voices...")
        appScope.launch {
            val builtIn = piperStore.installed(option.code)
            val system = collectVoices(option.code)
            val hasCatalog = PiperVoiceCatalog.forLanguage(option.code).isNotEmpty()

            val items = mutableListOf<VoiceMenuItem>(VoiceMenuItem.Automatic("Automatic (Babeltrout picks)"))
            builtIn.forEach { items += VoiceMenuItem.BuiltIn(it) }
            system.forEach { items += VoiceMenuItem.System(it, "${it.engineLabel}: ${describeVoice(it.voice)}") }
            if (hasCatalog) items += VoiceMenuItem.Library("Download more ${option.label} voices...")

            if (builtIn.isEmpty() && system.isEmpty() && !hasCatalog) {
                setStatus("No ${option.label} voices found. Install one via Voice Settings.", isError = true)
                return@launch
            }

            val pinned = pinnedVoiceFor(option.code)
            val checked = items.indexOfFirst { item ->
                when (item) {
                    is VoiceMenuItem.Automatic -> pinned == null
                    is VoiceMenuItem.BuiltIn -> pinned?.first == IN_APP_ENGINE && pinned.second == item.installed.key
                    is VoiceMenuItem.System -> pinned?.first == item.choice.enginePackage && pinned.second == item.choice.voice.name
                    is VoiceMenuItem.Library -> false
                }
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("${option.label} voice (${builtIn.size + system.size} installed)")
                .setSingleChoiceItems(items.map { it.label }.toTypedArray(), checked) { dialog, which ->
                    dialog.dismiss()
                    when (val item = items[which]) {
                        is VoiceMenuItem.Library -> {
                            showVoiceLibrary(option)
                            return@setSingleChoiceItems
                        }
                        is VoiceMenuItem.Automatic -> {
                            prefs.edit().remove(PREF_VOICE_PREFIX + option.code).apply()
                            setStatus("${option.label} voice: automatic.")
                        }
                        is VoiceMenuItem.BuiltIn -> {
                            pinVoice(option.code, IN_APP_ENGINE, item.installed.key)
                            setStatus("${option.label} voice: ${item.installed.label} (built-in). Playing sample...")
                        }
                        is VoiceMenuItem.System -> {
                            pinVoice(option.code, item.choice.enginePackage, item.choice.voice.name)
                            setStatus("${option.label} voice: ${item.choice.voice.name} (${item.choice.engineLabel}). Playing sample...")
                        }
                    }
                    speakTarget(option.sampleText, option.code)
                }
                .setNegativeButton("Cancel", null)
                .show()
            setStatus("Found ${builtIn.size + system.size} ${option.label} voice(s).")
        }
    }

    // ---- Voice library: built-in Piper voices you can download --------------------------------

    private fun showVoiceLibraryLanguagePicker() {
        val languages = languageOptions.filter { PiperVoiceCatalog.forLanguage(it.code).isNotEmpty() }
        AlertDialog.Builder(this)
            .setTitle("Voice Library")
            .setItems(languages.map { option ->
                val installed = piperStore.installed(option.code).size
                "${option.label} (${PiperVoiceCatalog.forLanguage(option.code).size} voices, $installed installed)"
            }.toTypedArray()) { _, which -> showVoiceLibrary(languages[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showVoiceLibrary(option: LanguageOption) {
        val voices = PiperVoiceCatalog.forLanguage(option.code)
        val installed = piperStore.installed(option.code)
        val labels = voices.map { voice ->
            val have = installed.filter { it.voice.id == voice.id }
            if (have.isNotEmpty()) {
                "${voice.displayName}: installed (${have.joinToString { it.quality.label }})"
            } else {
                val compact = voice.pkg(PiperVoiceCatalog.Quality.COMPACT)?.sizeMb
                val full = voice.pkg(PiperVoiceCatalog.Quality.FULL)?.sizeMb
                "${voice.displayName}: download ($compact MB compact / $full MB full)"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("${option.label} voices")
            .setItems(labels.toTypedArray()) { _, which -> showVoiceActions(option, voices[which]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showVoiceActions(option: LanguageOption, voice: PiperVoiceCatalog.Voice) {
        val have = piperStore.installed(option.code).filter { it.voice.id == voice.id }
        val compact = voice.pkg(PiperVoiceCatalog.Quality.COMPACT)
        val full = voice.pkg(PiperVoiceCatalog.Quality.FULL)
        val message = buildString {
            append(voice.description)
            append("\n\nLicense: ${voice.license}. Runs offline inside Babeltrout.")
            append("\nCompact is smaller and a little faster; full quality is the original model.")
            if (have.isNotEmpty()) append("\n\nInstalled: ${have.joinToString { it.quality.label }}.")
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(voice.displayName)
            .setMessage(message)

        if (have.isNotEmpty()) {
            val current = have.first()
            builder.setPositiveButton("Use & play sample") { _, _ ->
                pinVoice(option.code, IN_APP_ENGINE, current.key)
                setStatus("${option.label} voice: ${current.label} (built-in).")
                speakTarget(option.sampleText, option.code)
            }
            builder.setNegativeButton("Delete") { _, _ -> confirmDeleteVoice(option, have) }
            val missing = listOfNotNull(compact, full).firstOrNull { pkg -> have.none { it.quality == pkg.quality } }
            if (missing != null) {
                builder.setNeutralButton("Get ${missing.quality.label} (${missing.sizeMb} MB)") { _, _ ->
                    startVoiceDownload(option, voice, missing)
                }
            }
        } else {
            compact?.let { pkg ->
                builder.setPositiveButton("Compact (${pkg.sizeMb} MB)") { _, _ -> startVoiceDownload(option, voice, pkg) }
            }
            full?.let { pkg ->
                builder.setNeutralButton("Full (${pkg.sizeMb} MB)") { _, _ -> startVoiceDownload(option, voice, pkg) }
            }
            builder.setNegativeButton("Cancel", null)
        }
        builder.show()
    }

    private fun confirmDeleteVoice(option: LanguageOption, installed: List<PiperVoiceStore.Installed>) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${installed.first().voice.displayName}?")
            .setMessage("You can download it again later.")
            .setPositiveButton("Delete") { _, _ ->
                piperSpeaker.stop()
                installed.forEach { piperStore.delete(it) }
                val pinned = pinnedVoiceFor(option.code)
                if (pinned?.first == IN_APP_ENGINE && installed.any { it.key == pinned.second }) {
                    prefs.edit().remove(PREF_VOICE_PREFIX + option.code).apply()
                }
                setStatus("Deleted ${installed.first().voice.displayName}.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isOnMeteredNetwork(): Boolean =
        runCatching { getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true }.getOrDefault(false)

    private fun startVoiceDownload(option: LanguageOption, voice: PiperVoiceCatalog.Voice, pkg: PiperVoiceCatalog.Package) {
        if (isDownloadingVoice) {
            setStatus("A voice download is already running.")
            return
        }
        if (isOnMeteredNetwork()) {
            AlertDialog.Builder(this)
                .setTitle("Use mobile data?")
                .setMessage("${voice.displayName} (${pkg.quality.label}) is ${pkg.sizeMb} MB and you're not on Wi-Fi.")
                .setPositiveButton("Download") { _, _ -> downloadVoice(option, voice, pkg) }
                .setNegativeButton("Wait for Wi-Fi", null)
                .show()
            return
        }
        downloadVoice(option, voice, pkg)
    }

    private fun downloadVoice(option: LanguageOption, voice: PiperVoiceCatalog.Voice, pkg: PiperVoiceCatalog.Package) {
        isDownloadingVoice = true
        binding.btnVoiceLibrary.isEnabled = false
        setStatus("Downloading ${voice.displayName} (${pkg.sizeMb} MB)...")
        appScope.launch {
            runCatching {
                piperStore.install(voice, pkg) { done, total ->
                    val percent = if (total > 0) (done * 100 / total).toInt() else 0
                    runOnUiThread {
                        setStatus("Downloading ${voice.displayName}: $percent% (${done / 1_000_000} of ${pkg.sizeMb} MB)")
                    }
                }
            }.onSuccess { installed ->
                // A freshly downloaded voice is almost always the one the user wants to hear next.
                pinVoice(option.code, IN_APP_ENGINE, installed.key)
                setStatus("${installed.label} installed and selected for ${option.label}. Playing sample...")
                speakTarget(option.sampleText, option.code)
                checkVoiceSupport(manual = false)
            }.onFailure { error ->
                setStatus("Voice download failed: ${error.message}", isError = true)
            }
            isDownloadingVoice = false
            binding.btnVoiceLibrary.isEnabled = true
        }
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
            fixes += "Tap Voice Library and download a built-in Farsi voice (recommended), or install SherpaTTS."
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
                    fixes += "Tap Import Voice Files and select a Piper Farsi voice (.onnx and .onnx.json, e.g. fa_IR-amir-medium), then enable that voice in SherpaTTS."
                }
                if (sherpaProbe.faLanguageAvailable && sherpaProbe.faVoiceCount == 0) {
                    issues += "SherpaTTS shows Farsi language support but no Farsi voice entries."
                    fixes += "Inside SherpaTTS, select a Farsi model as the active voice."
                }
                if (sherpaProbe.faLanguageAvailable && !sherpaProbe.speakOk) {
                    issues += "SherpaTTS failed the Farsi speak callback test."
                    fixes += "Inside SherpaTTS, run its own voice test and verify the selected Farsi voice plays audio."
                }
            }
        }

        val builtInFarsi = piperStore.installed("fa")
        lines += ""
        lines += "Built-in Farsi voices: ${builtInFarsi.size}"
        builtInFarsi.forEach { lines += "- ${it.label}" }
        inAppVoiceFor("fa")?.let { lines += "Farsi output uses built-in voice: ${it.label}" }
        if (builtInFarsi.isNotEmpty()) {
            // A built-in voice makes SherpaTTS optional, so its absence or misconfiguration isn't a problem.
            issues.clear()
            fixes.clear()
        }

        val allFarsiVoices = collectVoices("fa")
        lines += ""
        lines += "Farsi voices across all engines: ${allFarsiVoices.size}"
        allFarsiVoices.forEach { lines += "- ${it.engineLabel}: ${describeVoice(it.voice)}" }
        val pinnedFarsi = pinnedVoiceFor("fa")
        lines += "Chosen Farsi voice: ${pinnedFarsi?.let { pinnedLabel(it) } ?: "automatic"}"

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
            packageInfo.versionName ?: PackageInfoCompat.getLongVersionCode(packageInfo).toString()
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
            // ML Kit stores one model per language (each translates to/from English), so download
            // each language once rather than checking every en<->xx pair.
            val toDownload = requiredModelCodes.filter { it != "en" }.sorted()

            for ((index, code) in toDownload.withIndex()) {
                setStatus("Installing translation models ${index + 1}/${toDownload.size}: ${labelForCode(code)}")
                runCatching {
                    ensureLanguageModel(code)
                }.onFailure { error ->
                    failures.add("${labelForCode(code)}: ${error.message}")
                }
            }

            // Only when the user asks: voices and speech packs can be large (first run gets models only).
            val packSummary = if (manual) installSystemLanguagePacks() else ""

            binding.btnInstallAssets.isEnabled = true
            if (!isCheckingAssets) {
                binding.btnCheckAssets.isEnabled = true
            }
            isInstallingAssets = false

            if (failures.isEmpty()) {
                prefs.edit().putBoolean("assets_ready", true).apply()
                setStatus("All offline translation assets are installed. $packSummary".trim())
            } else {
                prefs.edit().putBoolean("assets_ready", false).apply()
                val summary = failures.firstOrNull() ?: "unknown issue"
                setStatus("Asset install partial: $summary. $packSummary".trim(), isError = true)
            }

            checkDownloadedAssets(manual = false)
        }
    }

    /**
     * Downloads Google's offline voice and (Android 13+) the on-device speech recognition pack for every
     * language, so first use doesn't pause. Returns a summary for the status line.
     */
    private suspend fun installSystemLanguagePacks(): String {
        val parts = mutableListOf<String>()

        val tts = resolveGoogleTtsPackageName()?.let { createTtsForProbe(it) }
        if (tts == null) {
            parts += "Voices: Google speech engine not found."
        } else {
            try {
                val outcomes = languageOptions.mapIndexed { index, option ->
                    setStatus("Installing voices ${index + 1}/${languageOptions.size}: ${option.label}")
                    option to SystemLanguagePacks.prefetchVoice(tts, option, cacheDir)
                }
                parts += "Voices: ${describePackOutcomes(outcomes)}."
            } finally {
                tts.stop()
                tts.shutdown()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val packs = SystemLanguagePacks.RecognitionPacks.create(this)
            if (packs == null) {
                parts += "Offline speech recognition isn't available on this phone."
            } else {
                try {
                    val outcomes = languageOptions.mapIndexed { index, option ->
                        setStatus("Installing speech recognition ${index + 1}/${languageOptions.size}: ${option.label}")
                        option to packs.ensure(option)
                    }
                    parts += "Offline speech recognition: ${describePackOutcomes(outcomes)}."
                } finally {
                    packs.destroy()
                }
            }
        }
        return parts.joinToString(" ")
    }

    private fun describePackOutcomes(outcomes: List<Pair<LanguageOption, SystemLanguagePacks.Outcome>>): String =
        outcomes.groupBy({ it.second }, { it.first.label })
            .toSortedMap()
            .entries
            .joinToString("; ") { (outcome, labels) ->
                val heading = when (outcome) {
                    SystemLanguagePacks.Outcome.READY -> "ready"
                    SystemLanguagePacks.Outcome.DOWNLOADED -> "downloaded"
                    SystemLanguagePacks.Outcome.DOWNLOADING -> "still downloading"
                    SystemLanguagePacks.Outcome.NOT_OFFERED -> "not offered"
                    SystemLanguagePacks.Outcome.FAILED -> "failed"
                }
                "$heading ${labels.joinToString(", ")}"
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

    private suspend fun ensureLanguageModel(code: String) {
        val language = TranslateLanguage.fromLanguageTag(code)
            ?: error("Translation language not supported: $code")
        val model = TranslateRemoteModel.Builder(language).build()
        withContext(Dispatchers.IO) {
            remoteModelManager.download(model, modelDownloadConditions).await()
        }
    }

    /**
     * ML Kit translates every non-English pair by pivoting through English internally, so a
     * single call covers all routes. The route list is informational (shown in the status line).
     */
    private suspend fun translateText(sourceText: String, sourceCode: String, targetCode: String): Pair<String, List<String>> {
        if (sourceCode == targetCode) {
            return sourceText to emptyList()
        }

        val translated = translateDirect(sourceText, sourceCode, targetCode)
        val route = if (sourceCode == "en" || targetCode == "en") {
            listOf("$sourceCode->$targetCode")
        } else {
            listOf("$sourceCode->en", "en->$targetCode")
        }
        return translated to route
    }

    private suspend fun translateDirect(sourceText: String, sourceCode: String, targetCode: String): String {
        return withContext(Dispatchers.IO) {
            val translator = getTranslator(sourceCode, targetCode)
            val key = "$sourceCode->$targetCode"
            if (key !in readyTranslatorPairs) {
                translator.downloadModelIfNeeded(modelDownloadConditions).await()
                synchronized(readyTranslatorPairs) { readyTranslatorPairs += key }
            }
            translator.translate(sourceText).await()
        }
    }

    private fun getTranslator(sourceCode: String, targetCode: String): Translator {
        val key = "$sourceCode->$targetCode"
        synchronized(translatorCache) {
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
    }

    private suspend fun detectSourceLanguage(transcript: String): String {
        val idResult = runCatching {
            languageIdentifier.identifyLanguage(transcript).await()
        }.getOrDefault("und")

        val normalized = normalizeCode(idResult)
        if (normalized in supportedCodes) {
            return normalized
        }
        return ScriptHeuristics.guessFromScript(transcript)
    }

    private fun buildTransliteration(targetText: String, targetCode: String, sourceCode: String): TransliterationResult {
        val none = TransliterationResult(false, "", "", "", "")
        if (Languages.script(targetCode) == Script.LATIN) {
            return none
        }

        val latin = TransliterationEngine.toLatin(targetText, targetCode)
        if (latin.isBlank()) {
            return none
        }

        // Write the pronunciation in the speaker's own script, or Latin if they share the target's script.
        val scriptCode = when {
            sourceCode !in supportedCodes -> "en"
            Languages.script(sourceCode) == Languages.script(targetCode) -> "en"
            else -> sourceCode
        }
        val scriptText = TransliterationEngine.latinToScript(latin, scriptCode)

        return TransliterationResult(
            available = scriptText.isNotBlank(),
            scriptCode = scriptCode,
            scriptLabel = if (Languages.script(scriptCode) == Script.LATIN) "Latin" else labelForCode(scriptCode),
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
        val rtl = Languages.isRtl(code)
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
                val supported = inAppVoiceFor(option.code) != null || resolveRouteForOutputCode(option.code).let { route ->
                    route != null && isVoiceAvailable(route.tts, option.code)
                }
                availableTtsByCode[option.code] = supported
                if (!supported) {
                    missingLabels.add(option.label)
                }
            }

            val message = if (missingLabels.isEmpty()) {
                "Voices available for all app languages (auto-routed by language)."
            } else {
                "Missing voices: ${missingLabels.joinToString(", ")}. Tap Voice Library (Farsi, Hindi) or Voice Settings."
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

    private fun configureVoiceForCode(tts: TextToSpeech, code: String, pinnedVoiceName: String? = null): Boolean {
        val normalized = normalizeCode(code)
        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
        if (pinnedVoiceName != null) {
            voices.firstOrNull { it.name == pinnedVoiceName }?.let { voice ->
                tts.voice = voice
                return true
            }
        }
        val matchingVoice = voices
            .filter { normalizeCode(it.locale.toLanguageTag()) == normalized }
            .filterNot { it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedBy { if (it.isNetworkConnectionRequired) 1 else 0 }
            .firstOrNull()

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

    /** Resolves an engine + voice for [code] and applies speech rate. Null (with a status message) if none. */
    private suspend fun prepareTtsFor(code: String): TtsEngineRoute? {
        val route = resolveRouteForOutputCode(code)
        if (route == null) {
            availableTtsByCode[code] = false
            setStatus("No text-to-speech engine is available for ${labelForCode(code)}.", isError = true)
            return null
        }

        if (!configureVoiceForCode(route.tts, code, route.pinnedVoiceName)) {
            availableTtsByCode[code] = false
            val engineName = engineLabelForPackage(route.enginePackage)
            setStatus("No ${labelForCode(code)} voice available in $engineName.", isError = true)
            return null
        }

        availableTtsByCode[code] = true
        route.tts.setSpeechRate(currentSpeechRate())
        return route
    }

    /**
     * Queues [text] in chunks the engine can accept. Returns the utterance IDs in order, or an
     * empty list if the engine refused the first chunk.
     */
    private fun enqueueSpeech(tts: TextToSpeech, text: String, idPrefix: String): List<String> {
        val maxLength = runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(4000)
        val ids = mutableListOf<String>()
        SpeechText.chunk(text, maxLength).forEachIndexed { index, chunk ->
            val id = "$idPrefix-${SystemClock.elapsedRealtime()}-$index"
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(chunk, mode, null, id) == TextToSpeech.ERROR) {
                return if (index == 0) emptyList() else ids
            }
            ids += id
        }
        return ids
    }

    private fun speakTarget(text: String, targetCode: String) {
        val code = normalizeCode(targetCode)
        inAppVoiceFor(code)?.let { voice ->
            stopAllSpeech()
            availableTtsByCode[code] = true
            appScope.launch {
                runCatching { piperSpeaker.speak(voice, text, currentSpeechRate()) }
                    .onFailure { error -> setStatus("Built-in voice ${voice.label} failed: ${error.message}", isError = true) }
            }
            return
        }

        appScope.launch {
            piperSpeaker.stop()
            val route = prepareTtsFor(code) ?: return@launch
            if (enqueueSpeech(route.tts, text, "speak").isEmpty()) {
                val engineName = engineLabelForPackage(route.enginePackage)
                setStatus("Speech playback failed in $engineName.", isError = true)
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private suspend fun speakTargetAndWait(text: String, targetCode: String): Boolean {
        val code = normalizeCode(targetCode)
        inAppVoiceFor(code)?.let { voice ->
            availableTtsByCode[code] = true
            isConverseSpeaking = true
            val completed = runCatching {
                withTimeoutOrNull(SpeechText.speakTimeoutMillis(text, currentSpeechRate())) {
                    piperSpeaker.speak(voice, text, currentSpeechRate()) {
                        appScope.launch { if (isConverseActive) updateConverseStatus("Speaking ${labelForCode(code)}...") }
                    }
                } ?: false
            }.getOrElse { error ->
                setStatus("Built-in voice ${voice.label} failed: ${error.message}", isError = true)
                false
            }
            if (!completed) piperSpeaker.stop()
            isConverseSpeaking = false
            return completed
        }

        val route = prepareTtsFor(code) ?: return false
        val tts = route.tts
        isConverseSpeaking = true

        val completed = withTimeoutOrNull(SpeechText.speakTimeoutMillis(text, currentSpeechRate())) {
            suspendCancellableCoroutine { continuation ->
                // onDone arrives on a binder thread and can beat us to recording the last ID.
                val lock = Any()
                var lastUtteranceId = ""
                val finishedIds = mutableSetOf<String>()
                val ourPrefix = "converse-"
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceIdFromEngine: String?) {
                        if (utteranceIdFromEngine?.startsWith(ourPrefix) == true && isConverseActive) {
                            appScope.launch {
                                updateConverseStatus("Speaking ${labelForCode(code)}...")
                            }
                        }
                    }

                    override fun onDone(utteranceIdFromEngine: String?) {
                        val isLast = synchronized(lock) {
                            utteranceIdFromEngine?.let { finishedIds += it }
                            utteranceIdFromEngine == lastUtteranceId
                        }
                        if (isLast && continuation.isActive) {
                            continuation.resume(true)
                        }
                    }

                    override fun onError(utteranceIdFromEngine: String?) {
                        if (utteranceIdFromEngine?.startsWith(ourPrefix) == true && continuation.isActive) {
                            continuation.resume(false)
                        }
                    }

                    override fun onError(utteranceIdFromEngine: String?, errorCode: Int) {
                        if (utteranceIdFromEngine?.startsWith(ourPrefix) == true && continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                })

                val ids = enqueueSpeech(tts, text, "converse")
                if (ids.isEmpty()) {
                    if (continuation.isActive) continuation.resume(false)
                } else {
                    val alreadyDone = synchronized(lock) {
                        lastUtteranceId = ids.last()
                        lastUtteranceId in finishedIds
                    }
                    if (alreadyDone && continuation.isActive) continuation.resume(true)
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
        var bestScore = ScriptHeuristics.scoreAutoCandidate(bestTranscript, bestSource, 0)

        for (index in 1 until candidates.size) {
            val candidate = candidates[index]
            val detectedSource = detectSourceLanguage(candidate)
            val candidateScore = ScriptHeuristics.scoreAutoCandidate(candidate, detectedSource, index)
            if (candidateScore > bestScore) {
                bestTranscript = candidate
                bestSource = detectedSource
                bestScore = candidateScore
            }
        }

        return bestTranscript to bestSource
    }

    private fun pickBestTranscriptForForcedSource(candidates: List<String>, sourceCode: String): String =
        ScriptHeuristics.pickBestForcedTranscript(candidates, sourceCode)

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

    private fun labelForCode(code: String): String = Languages.label(code)

    private fun localeTagForCode(code: String): String = Languages.localeTag(code)

    private fun recognitionTagForCode(code: String): String {
        val normalized = normalizeCode(code)
        return if (normalized in supportedCodes) normalized else localeTagForCode(code)
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
        piperSpeaker.release()
        appScope.cancel()
    }

    private fun normalizeCode(code: String?): String = Languages.normalizeCode(code)

    private companion object {
        const val PREF_TARGET_CODE = "target_code"
        const val PREF_CONVERSE_A = "converse_code_a"
        const val PREF_CONVERSE_B = "converse_code_b"
        const val PREF_CONVERSE_SEGMENTED = "converse_segmented_session"
        const val PREF_CONVERSE_HANDS_FREE = "converse_hands_free"
        const val HANDS_FREE_MAX_EMPTY_TURNS = 3
        const val PREF_SPEECH_RATE_PROGRESS = "speech_rate_progress"
        const val PREF_OUTPUT_SIZE_PROGRESS = "output_size_progress"
        const val PREF_VOICE_PREFIX = "voice_"
        const val PINNED_VOICE_SEPARATOR = "|"
        /** Pseudo engine package for built-in Piper voices in pinned-voice preferences. */
        const val IN_APP_ENGINE = "babeltrout.piper"
    }
}
