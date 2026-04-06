package com.example.kylesmusicplayerandroid.domain.usecase.playlist

import android.content.Context
import android.util.Log
import com.example.kylesmusicplayerandroid.auth.GraphClient
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.time.Instant

/**
 * SyncPlaylistsUseCase
 *
 * Responsibility:
 *   Find the newest playlist backup JSON in OneDrive (playlist_backup_*.json / playlists_backup_*.json),
 *   download it, and overwrite local playlists.json (app-private storage).
 *
 * Notes:
 *   - OneDrive path is a STRING (Graph path)
 *   - No SAF, no DocumentFile
 *   - App-private storage only (context.filesDir)
 *   - Full rewrite (temp file + atomic-ish replace)
 */
class SyncPlaylistsUseCase {

    companion object {
        private const val TAG = "SyncPlaylistsUseCase"
    }

    suspend fun execute(
        context: Context,
        backupPlaylistPath: String
    ) {
        val folderPath = deriveFolderPath(backupPlaylistPath)

        val latestBackupName = findLatestBackupFileName(folderPath)
        if (latestBackupName.isNullOrBlank()) {
            Log.e(TAG, "No playlist backup found in OneDrive folder: $folderPath")
            return
        }

        val downloadPath = "$folderPath/$latestBackupName:/content"
        Log.w(TAG, "Syncing playlists from OneDrive file: $latestBackupName (path=$downloadPath)")

        val localFile = File(context.filesDir, "playlists.json")
        val tempFile = File(context.filesDir, "playlists.json.tmp")

        try {
            val stream: InputStream = GraphClient.download(downloadPath) ?: run {
                Log.e(TAG, "Download returned null stream for: $downloadPath")
                return
            }

            // Write to temp first (prevents partial/corrupt playlists.json if interrupted)
            tempFile.outputStream().use { output ->
                stream.use { input ->
                    input.copyTo(output)
                }
            }

            // Best-effort atomic replace on internal storage
            if (localFile.exists()) {
                val deleted = localFile.delete()
                if (!deleted) {
                    Log.w(TAG, "Warning: failed to delete existing playlists.json (will attempt overwrite)")
                }
            }

            val renamed = tempFile.renameTo(localFile)
            if (!renamed) {
                // Fallback: copy then delete temp
                Log.w(TAG, "renameTo() failed; falling back to copy")
                tempFile.copyTo(localFile, overwrite = true)
                try { tempFile.delete() } catch (_: Exception) {}
            }

            Log.w(TAG, "✅ Playlists synced to local playlists.json (bytes=${localFile.length()})")

        } catch (t: Throwable) {
            Log.e(TAG, "🔥 Playlist sync failed", t)
            try { if (tempFile.exists()) tempFile.delete() } catch (_: Throwable) {}
        }
    }

    private fun deriveFolderPath(path: String): String {
        val trimmed = path.trim().removeSuffix("/")
        return if (trimmed.endsWith(".json", ignoreCase = true) && trimmed.contains("/")) {
            trimmed.substringBeforeLast("/")
        } else {
            trimmed
        }
    }

    private suspend fun findLatestBackupFileName(folderPath: String): String? {

        val childrenApi =
            "$folderPath:/children" +
                    "?%24select=name,lastModifiedDateTime,file" +
                    "&%24top=999"

        val json = GraphClient.getJson(childrenApi) ?: return null
        val items = json.optJSONArray("value") ?: return null

        var bestName: String? = null
        var bestTime: Instant? = null

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue

            if (!item.has("file")) continue

            val name = item.optString("name", "").trim()
            if (name.isBlank()) continue

            val lower = name.lowercase()
            val isBackup =
                (lower.startsWith("playlist_backup_") || lower.startsWith("playlists_backup_")) &&
                        lower.endsWith(".json")

            if (!isBackup) continue

            val lm = item.optString("lastModifiedDateTime", "").trim()
            val parsed = parseInstantOrNull(lm) ?: continue

            if (bestTime == null || parsed.isAfter(bestTime)) {
                bestTime = parsed
                bestName = name
            }
        }

        return bestName
    }

    private fun parseInstantOrNull(s: String): Instant? {
        return try {
            if (s.isBlank()) null else Instant.parse(s)
        } catch (_: Exception) {
            null
        }
    }

    // Not used for /children endpoints. Kept as a pattern reference.
    private suspend fun loadCloudJson(contentPath: String): JSONObject? {
        return try {
            val stream: InputStream = GraphClient.download(contentPath) ?: return null
            stream.use {
                val text = it.reader().readText()
                JSONObject(text)
            }
        } catch (_: Exception) {
            null
        }
    }
}
