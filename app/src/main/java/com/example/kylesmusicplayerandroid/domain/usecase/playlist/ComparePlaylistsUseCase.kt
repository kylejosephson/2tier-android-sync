package com.example.kylesmusicplayerandroid.domain.usecase.playlist

import android.content.Context
import android.util.Log
import com.example.kylesmusicplayerandroid.auth.GraphClient
import com.example.kylesmusicplayerandroid.data.sync.PlaylistSyncInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.time.Instant

/**
 * Compares the latest OneDrive playlist JSON with the local playlists.json file.
 *
 * IMPORTANT:
 * - CHECK-ONLY. It does not write anything.
 * - We prefer the newest backup by lastModifiedDateTime from OneDrive.
 * - If timestamps are missing/unparseable, we fall back to newest-by-name.
 */
class ComparePlaylistsUseCase {

    companion object {
        private const val TAG = "ComparePlaylistsUseCase"

        // Accept more than just "playlist(s)_backup_*.json" to avoid false "synced"
        // when the desktop creates different naming patterns.
        private fun isPlaylistJsonCandidate(fileName: String): Boolean {
            val lower = fileName.lowercase()
            if (!lower.endsWith(".json")) return false

            // Prefer the known "source of truth" naming patterns (your backup-as-truth files)
            if (lower.startsWith("playlist_backup_")) return true
            if (lower.startsWith("playlists_backup_")) return true

            // Fallback: accept other plausible playlist json names
            // (keeps Compare from missing the real newest truth file)
            if (lower.startsWith("playlist_")) return true
            if (lower.startsWith("playlists_")) return true
            if (lower.contains("playlist") && lower.endsWith(".json")) return true

            return false
        }

        private fun isPreferredBackupName(fileName: String): Boolean {
            val lower = fileName.lowercase()
            return lower.startsWith("playlist_backup_") || lower.startsWith("playlists_backup_")
        }
    }

    suspend fun execute(
        context: Context,
        backupPlaylistPath: String,
        localPlaylistFile: java.io.File
    ): PlaylistSyncInfo {

        // 1) Find newest playlist JSON in OneDrive folder
        val folderPath = deriveFolderPath(backupPlaylistPath)

        val latest = findLatestPlaylistJson(folderPath)
            ?: return PlaylistSyncInfo(
                isSynced = false,
                status = "No playlist JSON found in OneDrive",
                missingPlaylists = emptyList(),
                extraPlaylists = emptyList()
            )

        val latestBackupName = latest.first
        val latestTime = latest.second
        Log.w(TAG, "Selected cloud playlist JSON: name='$latestBackupName' lastModified='${latestTime ?: "n/a"}' folder='$folderPath'")

        val latestBackupContentPath = "$folderPath/$latestBackupName:/content"

        // 2) Read JSON from OneDrive
        val backupJson = loadCloudJson(latestBackupContentPath)
            ?: return PlaylistSyncInfo(
                isSynced = false,
                status = "Unable to download playlist JSON from OneDrive",
                missingPlaylists = emptyList(),
                extraPlaylists = emptyList()
            )

        // 3) Read local playlists.json
        val localJson = loadLocalJson(localPlaylistFile)

        // 4) Compare keys and content
        val backupNames = backupJson.keys().asSequence().toSet()
        val localNames = localJson.keys().asSequence().toSet()

        val missing = (backupNames - localNames).toList().sorted()
        val extra = (localNames - backupNames).toList().sorted()

        val contentSame = jsonDeepEquals(backupJson, localJson)
        val synced = missing.isEmpty() && extra.isEmpty() && contentSame

        val status = when {
            synced -> "Playlists synced"
            missing.isEmpty() && extra.isEmpty() && !contentSame -> "Playlist contents differ"
            else -> "Playlist differences found"
        }

        Log.w(TAG, "Compare result: synced=$synced missing=${missing.size} extra=${extra.size} contentSame=$contentSame")

        return PlaylistSyncInfo(
            isSynced = synced,
            status = status,
            missingPlaylists = missing,
            extraPlaylists = extra
        )
    }

    private fun deriveFolderPath(path: String): String {
        val trimmed = path.trim().removeSuffix("/")
        return if (trimmed.endsWith(".json", ignoreCase = true) && trimmed.contains("/")) {
            trimmed.substringBeforeLast("/")
        } else {
            trimmed
        }
    }

    /**
     * Returns Pair(fileName, lastModifiedInstantOrNull)
     *
     * Selection strategy:
     * 1) Consider only file items with plausible playlist json names
     * 2) Prefer newest by parsed lastModifiedDateTime
     * 3) If timestamps missing/unparseable, fall back to newest-by-name
     * 4) If both exist, prefer "backup" prefix files when times tie
     */
    private suspend fun findLatestPlaylistJson(folderPath: String): Pair<String, Instant?>? {

        val childrenApi =
            "$folderPath:/children" +
                    "?%24select=name,lastModifiedDateTime,file" +
                    "&%24top=999"

        val json = GraphClient.getJson(childrenApi) ?: return null
        val items = json.optJSONArray("value") ?: return null

        data class Candidate(val name: String, val time: Instant?, val preferred: Boolean)

        val candidates = ArrayList<Candidate>()

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (!item.has("file")) continue

            val name = item.optString("name", "").trim()
            if (name.isBlank()) continue
            if (!isPlaylistJsonCandidate(name)) continue

            val lm = item.optString("lastModifiedDateTime", "").trim()
            val parsed = parseInstantOrNull(lm)
            candidates.add(Candidate(name = name, time = parsed, preferred = isPreferredBackupName(name)))
        }

        if (candidates.isEmpty()) return null

        // Helpful diagnostics (so you can see what OneDrive returned)
        val withTimes = candidates.count { it.time != null }
        Log.w(TAG, "Playlist JSON candidates found=${candidates.size} withParsedTimes=$withTimes")

        // 1) Prefer those with parsed timestamps (newest)
        val bestByTime = candidates
            .filter { it.time != null }
            .sortedWith(
                compareByDescending<Candidate> { it.time }
                    .thenByDescending { it.preferred } // prefer backup naming if same time
                    .thenByDescending { it.name }
            )
            .firstOrNull()

        if (bestByTime != null) return Pair(bestByTime.name, bestByTime.time)

        // 2) Fallback: newest by name (lexicographic)
        val bestByName = candidates
            .sortedWith(
                compareByDescending<Candidate> { it.preferred }
                    .thenByDescending { it.name }
            )
            .first()

        return Pair(bestByName.name, bestByName.time)
    }

    private fun parseInstantOrNull(s: String): Instant? {
        return try {
            if (s.isBlank()) null else Instant.parse(s)
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonDeepEquals(a: Any?, b: Any?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false

        val aNull = (a == JSONObject.NULL)
        val bNull = (b == JSONObject.NULL)
        if (aNull || bNull) return aNull && bNull

        return when {
            a is JSONObject && b is JSONObject -> jsonObjectEquals(a, b)
            a is JSONArray && b is JSONArray -> jsonArrayEquals(a, b)
            a is Number && b is Number -> numberEquals(a, b)
            else -> a == b
        }
    }

    private fun jsonObjectEquals(a: JSONObject, b: JSONObject): Boolean {
        val aKeys = a.keys().asSequence().toSet()
        val bKeys = b.keys().asSequence().toSet()
        if (aKeys != bKeys) return false

        for (k in aKeys) {
            if (!jsonDeepEquals(a.opt(k), b.opt(k))) return false
        }
        return true
    }

    private fun jsonArrayEquals(a: JSONArray, b: JSONArray): Boolean {
        if (a.length() != b.length()) return false
        for (i in 0 until a.length()) {
            if (!jsonDeepEquals(a.opt(i), b.opt(i))) return false
        }
        return true
    }

    private fun numberEquals(a: Number, b: Number): Boolean {
        return try {
            val aIsFloaty = (a is Float || a is Double)
            val bIsFloaty = (b is Float || b is Double)
            if (aIsFloaty || bIsFloaty) {
                a.toDouble() == b.toDouble()
            } else {
                a.toLong() == b.toLong()
            }
        } catch (_: Exception) {
            a.toString() == b.toString()
        }
    }

    private suspend fun loadCloudJson(contentPath: String): JSONObject? {
        return try {
            val stream: InputStream = GraphClient.download(contentPath) ?: return null
            stream.use {
                val text = it.reader().readText()
                JSONObject(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading cloud playlist JSON: $contentPath", e)
            null
        }
    }

    private fun loadLocalJson(file: java.io.File): JSONObject {
        return try {
            val text = if (file.exists()) file.readText() else "{}"
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
    }
}
