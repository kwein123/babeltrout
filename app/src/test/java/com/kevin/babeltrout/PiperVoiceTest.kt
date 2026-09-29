package com.kevin.babeltrout

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiperVoiceTest {

    // ---- catalog ---------------------------------------------------------------------------

    @Test
    fun `catalog has five farsi and three hindi voices, each in two sizes`() {
        assertEquals(5, PiperVoiceCatalog.forLanguage("fa").size)
        assertEquals(3, PiperVoiceCatalog.forLanguage("hi-IN").size)
        PiperVoiceCatalog.all.forEach { voice ->
            assertEquals(voice.id, 2, voice.packages.size)
            assertTrue(voice.languageCode in Languages.codes)
        }
    }

    @Test
    fun `catalog entries are well formed`() {
        val ids = PiperVoiceCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        PiperVoiceCatalog.all.flatMap { v -> v.packages.map { v to it } }.forEach { (voice, pkg) ->
            assertTrue(pkg.url, pkg.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"))
            assertTrue(pkg.url.endsWith("/${pkg.archiveDir}.tar.bz2"))
            assertTrue(pkg.sha256, pkg.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(pkg.sizeBytes in 10_000_000L..100_000_000L)
            assertTrue(voice.modelFile.endsWith("-medium.onnx"))
            assertTrue(pkg.archiveDir.contains(voice.id))
        }
        val compact = PiperVoiceCatalog.byId("fa_IR-amir")!!.pkg(PiperVoiceCatalog.Quality.COMPACT)!!
        assertEquals(21, compact.sizeMb)
    }

    // ---- archive path rules ----------------------------------------------------------------

    private val dir = "vits-piper-fa_IR-amir-medium-int8"

    @Test
    fun `relativePath accepts files inside the package directory`() {
        assertEquals("tokens.txt", PiperArchivePaths.relativePath("$dir/tokens.txt", dir))
        assertEquals("espeak-ng-data/fa_dict", PiperArchivePaths.relativePath("$dir/espeak-ng-data/fa_dict", dir))
    }

    @Test
    fun `relativePath rejects anything that could escape`() {
        assertNull(PiperArchivePaths.relativePath("other/tokens.txt", dir))
        assertNull(PiperArchivePaths.relativePath("$dir/../evil.so", dir))
        assertNull(PiperArchivePaths.relativePath("$dir/espeak-ng-data/../../evil", dir))
        assertNull(PiperArchivePaths.relativePath("$dir//etc/passwd", dir))
        assertNull(PiperArchivePaths.relativePath("$dir/a\\..\\b", dir))
        assertNull(PiperArchivePaths.relativePath("$dir/", dir))
        assertNull(PiperArchivePaths.relativePath("/$dir/tokens.txt", dir))
    }

    @Test
    fun `only needed files are unpacked`() {
        val model = "fa_IR-amir-medium.onnx"
        assertTrue(PiperArchivePaths.isWanted(model, model, includeEspeak = false))
        assertTrue(PiperArchivePaths.isWanted("$model.json", model, includeEspeak = false))
        assertTrue(PiperArchivePaths.isWanted("tokens.txt", model, includeEspeak = false))
        assertFalse(PiperArchivePaths.isWanted("run.sh", model, includeEspeak = true))
        assertFalse(PiperArchivePaths.isWanted("sub/tokens.txt", model, includeEspeak = true))
        assertTrue(PiperArchivePaths.isWanted("espeak-ng-data/fa_dict", model, includeEspeak = true))
        assertFalse(PiperArchivePaths.isWanted("espeak-ng-data/fa_dict", model, includeEspeak = false))
    }

    // ---- extraction end to end -------------------------------------------------------------

    private fun buildArchive(file: File, entries: List<Pair<String, String?>>, symlink: Pair<String, String>? = null) {
        TarArchiveOutputStream(BZip2CompressorOutputStream(file.outputStream())).use { tar ->
            for ((name, content) in entries) {
                if (content == null) {
                    tar.putArchiveEntry(TarArchiveEntry(name))
                } else {
                    val bytes = content.toByteArray()
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                    tar.write(bytes)
                }
                tar.closeArchiveEntry()
            }
            symlink?.let { (name, target) ->
                tar.putArchiveEntry(TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK).apply { linkName = target })
                tar.closeArchiveEntry()
            }
        }
    }

    @Test
    fun `extract keeps only safe wanted files and never follows links`() = runBlocking {
        val root = Files.createTempDirectory("piper").toFile()
        try {
            val voice = PiperVoiceCatalog.byId("fa_IR-amir")!!
            val archive = File(root, "voice.tar.bz2")
            buildArchive(
                archive,
                listOf(
                    "$dir/" to null,
                    "$dir/${voice.modelFile}" to "MODEL",
                    "$dir/${voice.modelFile}.json" to "{}",
                    "$dir/tokens.txt" to "a 1",
                    "$dir/MODEL_CARD" to "card",
                    "$dir/espeak-ng-data/fa_dict" to "dict",
                    "$dir/run.sh" to "rm -rf /",
                    "$dir/../escaped.txt" to "gotcha",
                    "elsewhere/tokens.txt" to "wrong dir",
                ),
                symlink = "$dir/espeak-ng-data/link" to "/etc/passwd",
            )
            val staging = File(root, "staging")
            PiperVoiceStore(File(root, "store")).extract(archive, dir, voice, staging)

            val files = staging.walk().filter { it.isFile }.map { it.relativeTo(staging).path }.sorted().toList()
            assertEquals(
                listOf("MODEL_CARD", "espeak-ng-data/fa_dict", voice.modelFile, "${voice.modelFile}.json", "tokens.txt").sorted(),
                files,
            )
            assertEquals("MODEL", File(staging, voice.modelFile).readText())
            assertFalse(File(root, "escaped.txt").exists())
            assertFalse(File(staging, "espeak-ng-data/link").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = java.io.IOException::class)
    fun `extract fails when the model is missing`(): Unit = runBlocking {
        val root = Files.createTempDirectory("piper").toFile()
        try {
            val voice = PiperVoiceCatalog.byId("fa_IR-amir")!!
            val archive = File(root, "voice.tar.bz2")
            buildArchive(archive, listOf("$dir/tokens.txt" to "a 1"))
            PiperVoiceStore(File(root, "store")).extract(archive, dir, voice, File(root, "staging"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `installed voices are discovered from disk`() {
        val root = Files.createTempDirectory("piper").toFile()
        try {
            val store = PiperVoiceStore(root)
            assertTrue(store.installed().isEmpty())
            val voice = PiperVoiceCatalog.byId("fa_IR-gyro")!!
            val dir = File(root, "voices/${voice.id}/COMPACT").apply { mkdirs() }
            File(dir, voice.modelFile).writeText("m")
            File(dir, "tokens.txt").writeText("t")
            assertTrue("no marker yet", store.installed().isEmpty())
            File(dir, ".installed").writeText("sha")
            assertTrue("no espeak data yet", store.installed().isEmpty())
            File(root, "espeak-ng-data").mkdirs()

            val installed = store.installed("fa")
            assertEquals(1, installed.size)
            assertEquals("fa_IR-gyro/COMPACT", installed.single().key)
            assertEquals(installed.single(), store.findByKey("fa_IR-gyro/COMPACT"))
            assertTrue(store.installed("hi").isEmpty())

            store.delete(installed.single())
            assertTrue(store.installed().isEmpty())
            assertFalse("shared espeak data removed with the last voice", File(root, "espeak-ng-data").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
