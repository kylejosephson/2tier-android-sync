package com.example.kylesmusicplayerandroid.data.sync

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.kylesmusicplayerandroid.data.metadata.SongEntry
import java.io.File
import java.io.FileOutputStream

/**
 * ArtworkSyncManager
 *
 * Downloads missing album artwork from OneDrive.
 *
 * Input:
 *   - SongEntry (contains artwork filename)
 *   - OneDrive /MusicPlayerData/artwork_backup_xxx folder
 *
 * Behavior:
 *   - Never overwrites artwork that exists on phone
 *   - Only downloads 1 image per album
 */
object ArtworkSyncManager {

    private const val ART_DIR = "artwork"   // inside filesDir

    /**
     * Download album artwork if missing.
     *
     * Returns: relative path usable by AppViewModel.resolveArtworkPath()
     */
    fun ensureArtwork(
        ctx: Context,
        entry: SongEntry,
        oneDriveArtworkFolder: DocumentFile?
    ): String {

        if (entry.artwork.isBlank()) return ""

        val localDir = File(ctx.filesDir, ART_DIR)
        if (!localDir.exists()) localDir.mkdirs()

        val filename = entry.artwork.substringAfterLast("/")
        val localFile = File(localDir, filename)

        // Already have it → done
        if (localFile.exists()) {
            return "artwork/$filename"
        }

        // Need to find the file in OneDrive
        val odFile = oneDriveArtworkFolder?.listFiles()
            ?.firstOrNull { it.name == filename }
            ?: return "" // not found in backup

        try {
            val stream = ctx.contentResolver.openInputStream(odFile.uri)
                ?: return ""

            FileOutputStream(localFile).use { out ->
                stream.copyTo(out)
            }

            return "artwork/$filename"

        } catch (e: Exception) {
            Log.e("ArtworkSync", "Failed artwork download: $filename", e)
        }

        return ""
    }
}