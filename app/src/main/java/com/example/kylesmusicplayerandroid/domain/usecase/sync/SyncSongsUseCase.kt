package com.example.kylesmusicplayerandroid.domain.usecase.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.kylesmusicplayerandroid.auth.GraphClient
import com.example.kylesmusicplayerandroid.data.storage.FileHashEntry
import com.example.kylesmusicplayerandroid.data.storage.SdCardIndex
import java.security.MessageDigest

class SyncSongsUseCase {

    companion object {
        private const val TAG = "SyncSongsUseCase"
        private const val MAX_DOWNLOADS = 50
        private const val PREFS_NAME = "app_prefs"
        private const val MUSIC_URI_KEY = "music_uri"

        private const val CACHE_SAMPLE_VERIFY = 25
    }

    suspend fun execute(context: Context): Boolean {

        val compare = CompareSongsUseCase().execute(context)
        val missingPaths: List<String> = compare.missingSongs

        Log.w(TAG, "Missing files reported: ${missingPaths.size}")

        if (missingPaths.isEmpty()) return true

        if (missingPaths.size > MAX_DOWNLOADS) {
            Log.e(TAG, "🔥 ABORTING — missing count exceeds $MAX_DOWNLOADS")
            return false
        }

        val missingSet = missingPaths
            .asSequence()
            .map { it.trim().trimStart('/') }
            .filter { it.isNotBlank() }
            .toSet()

        val sdCacheView = SdCardIndex.buildFromCacheOnly(
            context = context,
            requiredPaths = missingSet,
            sampleVerifyCount = CACHE_SAMPLE_VERIFY
        ) ?: return false

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(MUSIC_URI_KEY, null) ?: return false

        val musicRoot = DocumentFile.fromTreeUri(context, Uri.parse(uriString)) ?: return false

        var allSuccessful = true
        val downloadedUpdates = LinkedHashMap<String, FileHashEntry>()

        for (relativePathRaw in missingPaths) {

            val cleaned = relativePathRaw.trim().trimStart('/')
            if (cleaned.isBlank()) continue

            if (sdCacheView.containsPath(cleaned)) {
                Log.w(TAG, "Skipping (already in SD cache): $cleaned")
                continue
            }

            val parts = cleaned.split("/").filter { it.isNotBlank() }
            if (parts.isEmpty()) continue

            var currentDir: DocumentFile? = musicRoot
            for (i in 0 until parts.lastIndex) {
                currentDir =
                    currentDir?.findFile(parts[i])
                        ?: currentDir?.createDirectory(parts[i])
            }

            val filename = parts.last()
            if (filename.isBlank()) continue

            if (currentDir?.findFile(filename) != null) {
                Log.w(TAG, "Skipping (file already exists): $cleaned")
                continue
            }

            val output = currentDir?.createFile(guessMimeType(filename), filename)
            if (output == null) {
                allSuccessful = false
                continue
            }

            val contentPath = "/me/drive/root:/Music/$cleaned:/content"

            var skipThisFile = false

            try {
                GraphClient.downloadFileStream(contentPath).use { input ->

                    val outStream = context.contentResolver.openOutputStream(output.uri)
                    if (outStream == null) {
                        Log.e(TAG, "🔥 Failed openOutputStream for: $cleaned")
                        allSuccessful = false
                        skipThisFile = true
                        return@use
                    }

                    val (sha256, bytesWritten) = outStream.use { out ->
                        copyAndSha256(input, out)
                    }

                    val sizeBytes =
                        try {
                            output.length().takeIf { it > 0 } ?: bytesWritten
                        } catch (_: Throwable) {
                            bytesWritten
                        }

                    val lastModified =
                        try {
                            output.lastModified()
                        } catch (_: Throwable) {
                            -1L
                        }

                    downloadedUpdates[cleaned] = FileHashEntry(
                        relativePath = cleaned,
                        sha256 = sha256,
                        sizeBytes = sizeBytes,
                        lastModified = lastModified
                    )

                    Log.w(TAG, "✅ Downloaded: $cleaned")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "🔥 Download failed for: $cleaned", t)
                allSuccessful = false
                continue
            }

            if (skipThisFile) continue
        }

        if (downloadedUpdates.isNotEmpty()) {
            try {
                SdCardIndex.mergeDownloadedEntries(context, downloadedUpdates)
            } catch (t: Throwable) {
                Log.e(TAG, "🔥 Failed merging SD cache (non-fatal)", t)
            }
        }

        return allSuccessful
    }

    private fun copyAndSha256(
        input: java.io.InputStream,
        output: java.io.OutputStream
    ): Pair<String, Long> {

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)

        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            total += read
        }

        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return sha to total
    }

    private fun guessMimeType(filename: String): String {
        return when (filename.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wma" -> "audio/x-ms-wma"
            else -> "application/octet-stream"
        }
    }
}
