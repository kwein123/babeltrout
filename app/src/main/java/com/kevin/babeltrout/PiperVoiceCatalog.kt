package com.kevin.babeltrout

/**
 * Downloadable Piper voices that Babeltrout runs itself through sherpa-onnx (no SherpaTTS needed).
 *
 * Packages come from k2-fsa's `tts-models` GitHub release. Sizes and SHA-256 digests were copied from
 * the GitHub release API on 2026-09-28; downloads are rejected unless both match.
 * Regenerate this list when adding voices (see docs/FARSI_VOICES.md).
 */
object PiperVoiceCatalog {

    enum class Quality(val label: String) {
        COMPACT("compact"),
        FULL("full quality"),
    }

    data class Package(
        val quality: Quality,
        val url: String,
        val sizeBytes: Long,
        val sha256: String,
        /** Top-level directory inside the archive. */
        val archiveDir: String,
    ) {
        val sizeMb: Int get() = ((sizeBytes + 500_000) / 1_000_000).toInt()
    }

    data class Voice(
        /** Stable key, e.g. "fa_IR-amir". Stored in preferences; never rename. */
        val id: String,
        val languageCode: String,
        val displayName: String,
        val description: String,
        val license: String,
        /** File name of the .onnx model inside the package. */
        val modelFile: String,
        val packages: List<Package>,
    ) {
        fun pkg(quality: Quality): Package? = packages.firstOrNull { it.quality == quality }
    }

    val all: List<Voice> = listOf(
        Voice(
            id = "fa_IR-amir",
            languageCode = "fa",
            displayName = "Amir",
            description = "Clear, neutral. The classic Farsi Piper voice.",
            license = "CC0",
            modelFile = "fa_IR-amir-medium.onnx",
            packages = listOf(
                Package(
                    Quality.COMPACT,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fa_IR-amir-medium-int8.tar.bz2",
                    20666906L,
                    "b79dbf0b6b9629a36fd541bfda83817ec12e2697557d87b918638445971eac46",
                    "vits-piper-fa_IR-amir-medium-int8",
                ),
                Package(
                    Quality.FULL,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fa_IR-amir-medium.tar.bz2",
                    67180373L,
                    "46af65c1cdd86ec300db3998f2bf31a68d9e38da930c0655c1ff6f610e84f864",
                    "vits-piper-fa_IR-amir-medium",
                ),
            ),
        ),
        Voice(
            id = "fa_IR-gyro",
            languageCode = "fa",
            displayName = "Gyro",
            description = "Male voice by gyroing; good without extra text normalization.",
            license = "see model card",
            modelFile = "fa_IR-gyro-medium.onnx",
            packages = listOf(
                Package(
                    Quality.COMPACT,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fa_IR-gyro-medium-int8.tar.bz2",
                    20964438L,
                    "99bd3b912261c1a87a3f7930555622b36174b3e459e6bc153ba5fc743b931d9d",
                    "vits-piper-fa_IR-gyro-medium-int8",
                ),
                Package(
                    Quality.FULL,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fa_IR-gyro-medium.tar.bz2",
                    67183308L,
                    "75dc485f0c0528b1b07800464e5c72f4229339919525b3f634aa89690988d30e",
                    "vits-piper-fa_IR-gyro-medium",
                ),
            ),
        ),
        Voice(
            id = "fa_IR-ganji",
            languageCode = "fa",
            displayName = "Ganji",
            description = "Datacula dataset.",
            license = "CC0",
            modelFile = "fa_IR-ganji-medium.onnx",
            packages = listOf(
                Package(
                    Quality.COMPACT,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fa_IR-ganji-medium-int8.tar.bz2",
                    20645796L,
                    "2ae4658c3a69ef4f92e09d0014f4af31ca6c95e9ae417de3c34544b5e3a98120",
                    "vits-piper-fa_IR-ganji-medium-int8",
                ),
                Package(
                    Quality.FULL,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fa_IR-ganji-medium.tar.bz2",
                    67186157L,
                    "6eae2acccd1b4460159fa5acdad4bb5d2df6d64d8da3e4029dbdff4db14e7a7a",
                    "vits-piper-fa_IR-ganji-medium",
                ),
            ),
        ),
        Voice(
            id = "fa_IR-ganji_adabi",
            languageCode = "fa",
            displayName = "Ganji (literary)",
            description = "Same dataset family, formal literary reading style.",
            license = "CC0",
            modelFile = "fa_IR-ganji_adabi-medium.onnx",
            packages = listOf(
                Package(
                    Quality.COMPACT,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fa_IR-ganji_adabi-medium-int8.tar.bz2",
                    20746706L,
                    "42cc4a913e9e5a703a5a5d758267dd38ae6d86b47d4defad1e826be7885b050c",
                    "vits-piper-fa_IR-ganji_adabi-medium-int8",
                ),
                Package(
                    Quality.FULL,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fa_IR-ganji_adabi-medium.tar.bz2",
                    67221431L,
                    "c2c64b5b6e6f2a68b5e5e566ba7d0c87932b5b110ae98ad8c865774263a1155c",
                    "vits-piper-fa_IR-ganji_adabi-medium",
                ),
            ),
        ),
        Voice(
            id = "fa_IR-reza_ibrahim",
            languageCode = "fa",
            displayName = "Reza Ibrahim",
            description = "Trained on recitation audio; also reads English words.",
            license = "CC0",
            modelFile = "fa_IR-reza_ibrahim-medium.onnx",
            packages = listOf(
                Package(
                    Quality.COMPACT,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fa_IR-reza_ibrahim-medium-int8.tar.bz2",
                    21052889L,
                    "82d19a4011d6ca75a755057adff6480eb2e8bacf0c5edb7dad788cd3168234ca",
                    "vits-piper-fa_IR-reza_ibrahim-medium-int8",
                ),
                Package(
                    Quality.FULL,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fa_IR-reza_ibrahim-medium.tar.bz2",
                    67187249L,
                    "c5a6c1da647f93e284c7956013dd2262210365b80a4870bc28f1ba7bc1f00bfe",
                    "vits-piper-fa_IR-reza_ibrahim-medium",
                ),
            ),
        ),
        Voice(
            id = "hi_IN-pratham",
            languageCode = "hi",
            displayName = "Pratham",
            description = "Hindi Piper voice.",
            license = "see model card",
            modelFile = "hi_IN-pratham-medium.onnx",
            packages = listOf(
                Package(
                    Quality.COMPACT,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-pratham-medium-int8.tar.bz2",
                    20987965L,
                    "20f568c56207c13b9a0d9478aec8b7d1449122e618aeebc7211f6abc942b58b7",
                    "vits-piper-hi_IN-pratham-medium-int8",
                ),
                Package(
                    Quality.FULL,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-pratham-medium.tar.bz2",
                    67238438L,
                    "2084d321e1d2752f2b64ed3012ba27751df01a80da46f52920098cdcb7e35648",
                    "vits-piper-hi_IN-pratham-medium",
                ),
            ),
        ),
        Voice(
            id = "hi_IN-priyamvada",
            languageCode = "hi",
            displayName = "Priyamvada",
            description = "Hindi Piper voice.",
            license = "see model card",
            modelFile = "hi_IN-priyamvada-medium.onnx",
            packages = listOf(
                Package(
                    Quality.COMPACT,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-priyamvada-medium-int8.tar.bz2",
                    21097319L,
                    "2997c7bbc211e524af83dfdffe7712942f10bd76c079a526af2e09d1f36a92f2",
                    "vits-piper-hi_IN-priyamvada-medium-int8",
                ),
                Package(
                    Quality.FULL,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-priyamvada-medium.tar.bz2",
                    67240610L,
                    "399d91cc97eb288725633261f26b715f9a971e3bf7ec4fa1d7910cd0080d37eb",
                    "vits-piper-hi_IN-priyamvada-medium",
                ),
            ),
        ),
        Voice(
            id = "hi_IN-rohan",
            languageCode = "hi",
            displayName = "Rohan",
            description = "Hindi Piper voice.",
            license = "see model card",
            modelFile = "hi_IN-rohan-medium.onnx",
            packages = listOf(
                Package(
                    Quality.COMPACT,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-rohan-medium-int8.tar.bz2",
                    21064499L,
                    "b306822fe16516d3c882e070a7205d781524bd9e8f2d121c3da3e504afe20e05",
                    "vits-piper-hi_IN-rohan-medium-int8",
                ),
                Package(
                    Quality.FULL,
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-rohan-medium.tar.bz2",
                    67221139L,
                    "f0bb7781724951e9c90c49f68abfe821cffcbc198a9ae0031366d045a6ad5488",
                    "vits-piper-hi_IN-rohan-medium",
                ),
            ),
        ),
    )

    fun forLanguage(code: String): List<Voice> = all.filter { it.languageCode == Languages.normalizeCode(code) }

    fun byId(id: String): Voice? = all.firstOrNull { it.id == id }
}

