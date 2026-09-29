package com.kevin.babeltrout

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import kotlin.coroutines.coroutineContext

/**
 * Downloads, verifies, unpacks and tracks built-in Piper voices.
 *
 * Layout under [rootDir] (app-private storage, excluded from backups):
 * ```
 * espeak-ng-data/               shared phonemizer data (identical in every package; kept once)
 * voices/<voiceId>/<quality>/   model .onnx, .onnx.json, tokens.txt, MODEL_CARD, .installed marker
 * tmp/                          in-progress downloads and extraction
 * ```
 */
class PiperVoiceStore(private val rootDir: File) {

    data class Installed(
        val voice: PiperVoiceCatalog.Voice,
        val quality: PiperVoiceCatalog.Quality,
        val dir: File,
    ) {
        val key: String get() = "${voice.id}/${quality.name}"
        val modelFile: File get() = File(dir, voice.modelFile)
        val tokensFile: File get() = File(dir, "tokens.txt")
        val label: String get() = "${voice.displayName} (${quality.label})"
    }

    val espeakDataDir: File get() = File(rootDir, "espeak-ng-data")
    private val voicesDir: File get() = File(rootDir, "voices")
    private val tmpDir: File get() = File(rootDir, "tmp")

    private fun dirFor(voice: PiperVoiceCatalog.Voice, quality: PiperVoiceCatalog.Quality) =
        File(File(voicesDir, voice.id), quality.name)

    fun installed(): List<Installed> = PiperVoiceCatalog.all.flatMap { voice ->
        PiperVoiceCatalog.Quality.values().mapNotNull { quality ->
            val dir = dirFor(voice, quality)
            val complete = File(dir, MARKER).isFile && File(dir, voice.modelFile).isFile && espeakDataDir.isDirectory
            if (complete) Installed(voice, quality, dir) else null
        }
    }

    fun installed(languageCode: String): List<Installed> {
        val code = Languages.normalizeCode(languageCode)
        return installed().filter { it.voice.languageCode == code }
    }

    fun findByKey(key: String): Installed? = installed().firstOrNull { it.key == key }

    fun delete(installed: Installed) {
        installed.dir.deleteRecursively()
        installed.dir.parentFile?.let { if (it.list().isNullOrEmpty()) it.delete() }
        if (installed().isEmpty()) {
            espeakDataDir.deleteRecursively() // last voice gone: reclaim the shared ~18 MB too
        }
    }

    /**
     * Downloads [pkg] for [voice], verifies size and SHA-256, and unpacks it.
     * [onProgress] receives (bytesDownloaded, totalBytes) on the IO thread.
     */
    suspend fun install(
        voice: PiperVoiceCatalog.Voice,
        pkg: PiperVoiceCatalog.Package,
        onProgress: (Long, Long) -> Unit,
    ): Installed = withContext(Dispatchers.IO) {
        tmpDir.mkdirs()
        val archive = File(tmpDir, "${pkg.archiveDir}.tar.bz2.part")
        val staging = File(tmpDir, "${pkg.archiveDir}.staging")
        try {
            download(pkg, archive, onProgress)
            staging.deleteRecursively()
            extract(archive, pkg.archiveDir, voice, staging)

            val target = dirFor(voice, pkg.quality)
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            val stagedEspeak = File(staging, "espeak-ng-data")
            if (!espeakDataDir.isDirectory && stagedEspeak.isDirectory && !stagedEspeak.renameTo(espeakDataDir)) {
                throw IOException("Could not install shared espeak-ng data")
            }
            stagedEspeak.deleteRecursively()
            if (!staging.renameTo(target)) {
                throw IOException("Could not move voice into place")
            }
            File(target, MARKER).writeText(pkg.sha256)
            Installed(voice, pkg.quality, target)
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private suspend fun download(pkg: PiperVoiceCatalog.Package, dest: File, onProgress: (Long, Long) -> Unit) {
        val connection = (URL(pkg.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true // GitHub redirects release assets to its CDN (https -> https)
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download failed: HTTP ${connection.responseCode}")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var lastReported = 0L
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > pkg.sizeBytes) throw IOException("Download is larger than expected; discarded")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        if (total - lastReported >= 256 * 1024) {
                            lastReported = total
                            onProgress(total, pkg.sizeBytes)
                        }
                    }
                }
            }
            onProgress(total, pkg.sizeBytes)
            if (total != pkg.sizeBytes) throw IOException("Download incomplete ($total of ${pkg.sizeBytes} bytes)")
            val actual = digest.digest().toHex()
            if (!actual.equals(pkg.sha256, ignoreCase = true)) {
                throw IOException("Checksum mismatch; download discarded")
            }
        } finally {
            connection.disconnect()
        }
    }

    internal suspend fun extract(archive: File, archiveDir: String, voice: PiperVoiceCatalog.Voice, staging: File) {
        val needEspeak = !espeakDataDir.isDirectory
        val stagingCanonical = staging.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = tar.nextEntry ?: break
                // Regular files only. commons-compress reports symlinks as "files", so reject links and
                // special entries explicitly; directories are created on demand.
                if (entry.isDirectory || entry.isSymbolicLink || entry.isLink || entry.isCharacterDevice ||
                    entry.isBlockDevice || entry.isFIFO || !entry.isFile
                ) continue
                val relative = PiperArchivePaths.relativePath(entry.name, archiveDir) ?: continue
                if (!PiperArchivePaths.isWanted(relative, voice.modelFile, needEspeak)) continue

                val out = File(staging, relative).canonicalFile
                if (!out.path.startsWith(stagingCanonical.path + File.separator)) continue
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it) }
            }
        }
        if (!File(staging, voice.modelFile).isFile || !File(staging, "tokens.txt").isFile) {
            throw IOException("Voice package is missing its model or tokens")
        }
        if (needEspeak && !File(staging, "espeak-ng-data").isDirectory) {
            throw IOException("Voice package is missing espeak-ng data")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val MARKER = ".installed"
    }
}

/** Pure path rules for unpacking voice archives, kept separate so they can be unit-tested. */
object PiperArchivePaths {

    /**
     * Maps a tar entry name to a path relative to the package's top-level directory, or null if the
     * entry is outside that directory or tries to escape it ("..", absolute paths, backslashes).
     */
    fun relativePath(entryName: String, archiveDir: String): String? {
        val prefix = "$archiveDir/"
        if (!entryName.startsWith(prefix)) return null
        val relative = entryName.removePrefix(prefix)
        if (relative.isEmpty() || relative.startsWith("/") || relative.contains('\\')) return null
        if (relative.split('/').any { it == ".." || it == "." || it.isEmpty() }) return null
        return relative
    }

    /** Only the files Babeltrout uses are unpacked; espeak data only when not already installed. */
    fun isWanted(relative: String, modelFile: String, includeEspeak: Boolean): Boolean = when {
        relative.startsWith("espeak-ng-data/") -> includeEspeak
        relative.contains('/') -> false
        else -> relative == modelFile || relative == "$modelFile.json" || relative == "tokens.txt" || relative == "MODEL_CARD"
    }
}
