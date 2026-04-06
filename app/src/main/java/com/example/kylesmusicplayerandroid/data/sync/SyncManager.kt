package com.example.kylesmusicplayerandroid.data.sync

import android.content.Context
import android.util.Log
import com.example.kylesmusicplayerandroid.auth.GraphClient
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object SyncManager {

    private const val TAG = "SyncManager"

    // ---------------------------------------------------------
    // BACKUP SET
    // ---------------------------------------------------------
    data class BackupSet(
        val playlistFile: String,
        val metadataFile: String,
        val artworkFolder: String?,   // OPTIONAL
        val musicRoot: String,
        val timestamp: String
    )

    private const val BACKUP_ROOT = "/me/drive/root:/MusicPlayerData"
    private const val MUSIC_ROOT = "/me/drive/root:/Music"

    // ---------------------------------------------------------
    // INTERNAL: LIST CHILDREN (Graph JSON)
    // ---------------------------------------------------------
    private suspend fun listChildren(path: String): List<JSONObject> {
        return try {
            val json = GraphClient.getJson("$path:/children") ?: run {
                Log.e(TAG, "listChildren failed: getJson returned null for $path")
                return emptyList()
            }

            val arr = json.optJSONArray("value") ?: run {
                Log.e(TAG, "listChildren failed: missing 'value' array for $path")
                return emptyList()
            }

            val out = ArrayList<JSONObject>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                if (obj != null) out.add(obj)
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "listChildren exception for $path", e)
            emptyList()
        }
    }

    // ---------------------------------------------------------
    // FIND LATEST BACKUP SET (Graph only)
    // ---------------------------------------------------------
    suspend fun findLatestBackupSet(context: Context): BackupSet? {

        val items = listChildren(BACKUP_ROOT)

        Log.d(TAG, "Items found in MusicPlayerData: ${items.size}")

        for (item in items) {
            Log.d(
                TAG,
                "Item -> " +
                        "name=${item.optString("name")}, " +
                        "isFolder=${item.optJSONObject("folder") != null}, " +
                        "lastModified=${item.optString("lastModifiedDateTime")}"
            )
        }

        if (items.isEmpty()) {
            Log.e(TAG, "MusicPlayerData folder empty or unreadable")
            return null
        }

        var latestPlaylists: Pair<String, OffsetDateTime>? = null
        var latestMetadata: Pair<String, OffsetDateTime>? = null
        var latestArtwork: Pair<String, OffsetDateTime>? = null

        for (item in items) {

            val name = item.optString("name", "")
            val lastModifiedString = item.optString("lastModifiedDateTime", null)
            val isFolder = item.optJSONObject("folder") != null

            if (lastModifiedString == null) {
                Log.w(TAG, "Skipping $name — no lastModifiedDateTime")
                continue
            }

            val lastModified = try {
                OffsetDateTime.parse(lastModifiedString, DateTimeFormatter.ISO_DATE_TIME)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping $name — invalid timestamp: $lastModifiedString")
                continue
            }

            when {
                name.startsWith("playlists_backup_") && name.endsWith(".json") -> {
                    Log.d(TAG, "Matched PLAYLIST file: $name")
                    if (latestPlaylists == null || lastModified.isAfter(latestPlaylists!!.second))
                        latestPlaylists = name to lastModified
                }

                name.startsWith("metadata_backup_") && name.endsWith(".json") -> {
                    Log.d(TAG, "Matched METADATA file: $name")
                    if (latestMetadata == null || lastModified.isAfter(latestMetadata!!.second))
                        latestMetadata = name to lastModified
                }

                name.startsWith("artwork_backup_") && isFolder -> {
                    Log.d(TAG, "Matched ARTWORK folder: $name")
                    if (latestArtwork == null || lastModified.isAfter(latestArtwork!!.second))
                        latestArtwork = name to lastModified
                }
            }
        }

        if (latestPlaylists == null || latestMetadata == null) {
            Log.e(
                TAG,
                "Required files missing. " +
                        "playlists=${latestPlaylists != null}, " +
                        "metadata=${latestMetadata != null}"
            )
            return null
        }

        Log.d(
            TAG,
            "Selected backup set -> " +
                    "playlists=${latestPlaylists!!.first}, " +
                    "metadata=${latestMetadata!!.first}, " +
                    "artwork=${latestArtwork?.first ?: "NONE"}"
        )

        return BackupSet(
            playlistFile = "$BACKUP_ROOT/${latestPlaylists!!.first}",
            metadataFile = "$BACKUP_ROOT/${latestMetadata!!.first}",
            artworkFolder = latestArtwork?.let { "$BACKUP_ROOT/${it.first}" },
            musicRoot = MUSIC_ROOT,
            timestamp = latestMetadata!!.second.toString()
        )
    }

    // ---------------------------------------------------------
    // DOWNLOAD ANY FILE FROM ONEDRIVE → LOCAL FILE
    // ---------------------------------------------------------
    suspend fun downloadFile(remotePath: String, local: File): Boolean =
        try {
            val stream = GraphClient.download("$remotePath:/content") ?: return false
            local.outputStream().use { stream.copyTo(it) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: $remotePath", e)
            false
        }

    // ---------------------------------------------------------
    // LIST ALL SONGS RECURSIVELY (OneDrive/Music)
    // ---------------------------------------------------------
    suspend fun listAllRemoteSongs(rootPath: String): List<String> {

        val found = mutableListOf<String>()

        suspend fun walk(path: String) {
            val children = listChildren(path)
            for (child in children) {
                val name = child.optString("name", "")
                if (name.isBlank()) continue

                val isFolder = child.optJSONObject("folder") != null
                if (isFolder) walk("$path/$name")
                else if (name.endsWith(".mp3", ignoreCase = true)) found.add(name)
            }
        }

        walk(rootPath)
        return found
    }

    // ---------------------------------------------------------
    // DOWNLOAD ONE SONG → APP-PRIVATE STORAGE
    // ---------------------------------------------------------
    suspend fun downloadSong(
        context: Context,
        backup: BackupSet,
        songName: String,
        outputDir: File
    ): Boolean {

        val fullPath =
            findSongPathRecursive(backup.musicRoot, songName)
                ?: return false

        return try {
            val stream =
                GraphClient.download("$fullPath:/content") as InputStream?
                    ?: return false

            File(outputDir, songName).outputStream().use { stream.copyTo(it) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Song download failed: $songName", e)
            false
        }
    }

    // ---------------------------------------------------------
    // RECURSIVE SEARCH FOR SONG PATH (Graph)
    // ---------------------------------------------------------
    private suspend fun findSongPathRecursive(folderPath: String, filename: String): String? {

        val children = listChildren(folderPath)

        for (child in children) {
            val name = child.optString("name", "")
            if (name.isBlank()) continue

            val isFolder = child.optJSONObject("folder") != null

            if (isFolder) {
                findSongPathRecursive("$folderPath/$name", filename)?.let {
                    return it
                }
            } else if (name.equals(filename, ignoreCase = true)) {
                return "$folderPath/$name"
            }
        }

        return null
    }
}
