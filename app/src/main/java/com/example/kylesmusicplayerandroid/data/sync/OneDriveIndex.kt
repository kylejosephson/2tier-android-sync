package com.example.kylesmusicplayerandroid.data.sync

import android.content.Context
import android.util.Log
import com.example.kylesmusicplayerandroid.auth.GraphClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class FileHashEntry(
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long
)

object OneDriveIndex {

    private const val TAG = "OneDriveIndex"

    // Local cache (app private storage)
    private const val CACHE_FILE = "onedrive_index.json"

    // Persisted delta token (app private storage)
    private const val DELTA_LINK_FILE = "onedrive_delta_link.txt"

    // OneDrive root folder (source of truth)
    private const val MUSIC_ROOT = "/me/drive/root:/Music"

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "wav", "aac", "m4a", "ogg", "wma"
    )

    // Prevent parallel load() calls (suspend-safe)
    private val loadMutex = Mutex()

    // If "delta" comes back with basically the whole library, treat it as INITIAL
    private const val INITIAL_DELTA_BULK_THRESHOLD = 200

    // Progress granularity (matches your existing log pacing)
    private const val PROGRESS_LOG_EVERY = 250

    // Hashing retry policy
    private const val HASH_MAX_ATTEMPTS = 3
    private const val HASH_BACKOFF_MS = 900L

    // Repair pass: re-hash a few previously-failed items each load (keeps launch fast)
    private const val REPAIR_MAX_PER_LOAD = 5

    // ------------------------------------------------------------
    // Public “simple” entry (keeps existing callers working)
    // ------------------------------------------------------------
    suspend fun load(context: Context): Map<String, FileHashEntry> {
        return load(
            context = context,
            forceRebuild = false,
            onStatus = null,
            onProgress = null
        )
    }

    // ------------------------------------------------------------
    // Public “gate-friendly” entry
    // ------------------------------------------------------------
    /**
     * @param forceRebuild
     *  - If true: deletes cache + delta token and rebuilds from scratch.
     *
     * @param onStatus
     *  - Called with human-friendly status strings.
     *
     * @param onProgress
     *  - Called with counts while hashing (e.g., 250, 500, 750...).
     */
    suspend fun load(
        context: Context,
        forceRebuild: Boolean,
        onStatus: ((String) -> Unit)?,
        onProgress: ((Int) -> Unit)?
    ): Map<String, FileHashEntry> = loadMutex.withLock {

        val cacheFile = File(context.filesDir, CACHE_FILE)
        val deltaFile = File(context.filesDir, DELTA_LINK_FILE)

        if (forceRebuild) {
            onStatus?.invoke("Resetting OneDrive cache…")
            try { if (cacheFile.exists()) cacheFile.delete() } catch (_: Throwable) {}
            try { if (deltaFile.exists()) deltaFile.delete() } catch (_: Throwable) {}
        }

        // --------------------------------------------------------
        // 1️⃣ Load cache if present
        // --------------------------------------------------------
        onStatus?.invoke("Loading OneDrive cache…")

        val cached: LinkedHashMap<String, FileHashEntry> =
            if (cacheFile.exists()) {
                val read = LinkedHashMap(readCache(cacheFile))
                if (read.isNotEmpty()) {
                    Log.w(TAG, "Loaded ${read.size} OneDrive audio files from cache")
                } else {
                    Log.w(TAG, "OneDrive cache exists but is empty — rebuilding")
                }
                read
            } else {
                LinkedHashMap()
            }

        // --------------------------------------------------------
        // 2️⃣ If cache empty, do full build (slow one-time)
        // --------------------------------------------------------
        if (cached.isEmpty()) {

            onStatus?.invoke("Indexing OneDrive music (hashing)…")
            val built = buildIndex(
                onProgress = { count ->
                    if (count % PROGRESS_LOG_EVERY == 0) {
                        onProgress?.invoke(count)
                    }
                }
            )

            if (built.isNotEmpty()) {
                onStatus?.invoke("Saving OneDrive index…")
                writeCache(cacheFile, built)
                Log.w(TAG, "Indexed ${built.size} OneDrive audio files (hash-based)")

                onStatus?.invoke("Seeding OneDrive delta token…")
                seedDeltaToken(context)

                onStatus?.invoke("OneDrive index ready")
                return@withLock built
            }

            Log.e(TAG, "Initial OneDrive walk returned 0 files — retrying")

            onStatus?.invoke("Retrying OneDrive index build…")
            val retry = buildIndex(
                onProgress = { count ->
                    if (count % PROGRESS_LOG_EVERY == 0) {
                        onProgress?.invoke(count)
                    }
                }
            )

            if (retry.isNotEmpty()) {
                onStatus?.invoke("Saving OneDrive index…")
                writeCache(cacheFile, retry)
                Log.w(TAG, "Indexed ${retry.size} OneDrive audio files (retry, hash-based)")

                onStatus?.invoke("Seeding OneDrive delta token…")
                seedDeltaToken(context)

                onStatus?.invoke("OneDrive index ready")
                return@withLock retry
            }

            Log.e(TAG, "FAILED to index OneDrive music after retry")
            onStatus?.invoke("OneDrive index FAILED")
            return@withLock emptyMap()
        }

        // --------------------------------------------------------
        // 3️⃣ Cache exists: apply delta (should be fast)
        // --------------------------------------------------------
        onStatus?.invoke("Applying OneDrive delta…")
        val (updated, didChangeFromDelta) = tryApplyDelta(context, cached)

        // --------------------------------------------------------
        // 4️⃣ Repair pass: try to re-hash a few blank-sha entries
        //     (covers “scar” files from past transient failures)
        // --------------------------------------------------------
        val repairedCount = tryRepairBlankHashes(updated, maxToRepair = REPAIR_MAX_PER_LOAD)

        val didChange = didChangeFromDelta || repairedCount > 0

        if (didChange) {
            onStatus?.invoke("Saving updated OneDrive index…")
            writeCache(cacheFile, updated)
            val extra = if (repairedCount > 0) " (repaired=$repairedCount)" else ""
            Log.w(TAG, "✅ OneDrive index ready: ${updated.size} files (updated$extra)")
        } else {
            Log.w(TAG, "✅ OneDrive index ready: ${updated.size} files (no delta changes)")
        }

        onStatus?.invoke("OneDrive index ready")
        return@withLock updated
    }

    // ============================================================
    // FULL BUILD (slow): walk folder tree + hash every audio file
    // ============================================================

    private suspend fun buildIndex(
        onProgress: ((Int) -> Unit)?
    ): Map<String, FileHashEntry> {
        val out = LinkedHashMap<String, FileHashEntry>()
        var count = 0

        suspend fun walk(oneDrivePath: String, relDir: String) {
            val children = listChildrenViaGraph(oneDrivePath)

            for (child in children) {
                val name = child.optString("name", "").trim()
                if (name.isBlank()) continue

                val isFolder = child.optJSONObject("folder") != null

                if (isFolder) {
                    val nextRelDir = if (relDir.isBlank()) name else "$relDir/$name"
                    walk("$oneDrivePath/$name", nextRelDir)
                    continue
                }

                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in AUDIO_EXTENSIONS) continue

                val relativePath = if (relDir.isBlank()) name else "$relDir/$name"
                val sizeBytes = child.optLong("size", -1L)

                // /me/drive/root:/Music/.../file.mp3:/content
                val fileContentPath = "$oneDrivePath/$name:/content"

                val sha256 = hashOneDriveFileWithRetry(
                    contentPath = fileContentPath,
                    relativePathForLog = relativePath
                )

                // IMPORTANT: Always include the file in the index.
                // If hashing failed, sha256 will be "" and can be repaired later.
                out[relativePath] = FileHashEntry(
                    relativePath = relativePath,
                    sha256 = sha256,
                    sizeBytes = sizeBytes
                )

                count++
                if (count % PROGRESS_LOG_EVERY == 0) {
                    Log.w(TAG, "Indexed $count OneDrive files so far…")
                    onProgress?.invoke(count)
                }
            }
        }

        return try {
            walk(MUSIC_ROOT, "")
            out
        } catch (e: Exception) {
            Log.e(TAG, "Exception while indexing OneDrive", e)
            emptyMap()
        }
    }

    /**
     * Lists children via GraphClient.getJson() (no dependency on GraphClient.listChildren()).
     * Supports paging via @odata.nextLink.
     *
     * Uses:
     *   /me/drive/root:/Music:/children?$select=name,size,folder,parentReference,deleted&$top=999
     */
    private suspend fun listChildrenViaGraph(oneDrivePath: String): List<JSONObject> {
        val items = ArrayList<JSONObject>()

        val firstUrl =
            "$oneDrivePath:/children" +
                    "?\$select=name,size,folder,parentReference,deleted,lastModifiedDateTime,file" +
                    "&\$top=999"

        var nextUrl: String? = firstUrl

        while (nextUrl != null) {
            val json = GraphClient.getJson(nextUrl) ?: break

            val array = json.optJSONArray("value")
            if (array != null) {
                for (i in 0 until array.length()) {
                    items.add(array.getJSONObject(i))
                }
            }

            val nl = json.optString("@odata.nextLink", "")
                .trim()
                .ifBlank { null }

            nextUrl = nl
        }

        return items
    }

    // ============================================================
    // DELTA SUPPORT (fast incremental updates)
    // ============================================================

    private suspend fun seedDeltaToken(context: Context) {
        try {
            Log.w(TAG, "Running OneDrive delta (initial seed)")
            val result = GraphClient.runDelta("$MUSIC_ROOT:/delta")
            val deltaLink = result.deltaLink
            if (!deltaLink.isNullOrBlank()) {
                File(context.filesDir, DELTA_LINK_FILE).writeText(deltaLink)
                Log.w(TAG, "✅ Stored initial OneDrive deltaLink")
            } else {
                Log.w(TAG, "Delta seed did not return a deltaLink (non-fatal)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed seeding delta token (non-fatal)", t)
        }
    }

    private suspend fun tryApplyDelta(
        context: Context,
        cached: LinkedHashMap<String, FileHashEntry>
    ): Pair<LinkedHashMap<String, FileHashEntry>, Boolean> {

        val deltaFile = File(context.filesDir, DELTA_LINK_FILE)
        val savedLink: String? =
            if (deltaFile.exists()) deltaFile.readText().trim().ifBlank { null } else null

        if (savedLink == null) {
            Log.w(TAG, "No deltaLink yet; seeding only (no hashing).")
            seedDeltaToken(context)
            return Pair(cached, false)
        }

        Log.w(TAG, "Running OneDrive delta (using saved deltaLink)")

        val result = try {
            GraphClient.runDelta(savedLink)
        } catch (t: Throwable) {
            Log.e(TAG, "Delta query failed (non-fatal); using cached index", t)
            return Pair(cached, false)
        }

        result.deltaLink?.let { link ->
            val trimmed = link.trim()
            if (trimmed.isNotBlank()) {
                deltaFile.writeText(trimmed)
            }
        }

        val itemCount = result.items.size
        if (itemCount >= INITIAL_DELTA_BULK_THRESHOLD) {
            Log.w(TAG, "Delta returned $itemCount items (bulk). Treating as initial; skipping re-hash.")
            return Pair(cached, false)
        }

        var upserts = 0
        var deletes = 0

        val out = LinkedHashMap<String, FileHashEntry>(cached)

        for (item in result.items) {
            val name = item.optString("name", "").trim()
            if (name.isBlank()) continue

            val isFolder = item.optJSONObject("folder") != null
            if (isFolder) continue

            val deleted = item.optJSONObject("deleted") != null

            val relPath = computeRelativePath(item, name) ?: continue

            val ext = relPath.substringAfterLast('.', "").lowercase()
            if (ext !in AUDIO_EXTENSIONS) continue

            if (deleted) {
                if (out.remove(relPath) != null) deletes++
                continue
            }

            val sizeBytes = item.optLong("size", -1L)
            val contentPath = "$MUSIC_ROOT/${relPath.trimStart('/')}:/content"

            val sha256 = hashOneDriveFileWithRetry(
                contentPath = contentPath,
                relativePathForLog = relPath
            )

            // IMPORTANT: Always upsert the file entry even if hash failed.
            // sha256 may be "" and will be repaired later.
            out[relPath] = FileHashEntry(
                relativePath = relPath,
                sha256 = sha256,
                sizeBytes = sizeBytes
            )
            upserts++
        }

        val changed = (upserts > 0 || deletes > 0)
        if (changed) {
            Log.w(TAG, "Delta applied: +/upd=$upserts, deleted=$deletes, total=${out.size}")
        } else {
            Log.w(TAG, "Delta applied: no changes, total=${out.size}")
        }

        return Pair(out, changed)
    }

    private fun computeRelativePath(item: JSONObject, name: String): String? {
        val parentRef = item.optJSONObject("parentReference") ?: return null
        val parentPath = parentRef.optString("path", "").trim()
        if (parentPath.isBlank()) return null

        val marker = ":/Music"
        val idx = parentPath.indexOf(marker)
        if (idx < 0) return null

        val after = parentPath.substring(idx + marker.length).trimStart('/')
        val relDir = after.trim('/')

        return if (relDir.isBlank()) name else "$relDir/$name"
    }

    // ============================================================
    // HASHING HELPERS (retry + repair)
    // ============================================================

    private suspend fun hashOneDriveFileWithRetry(
        contentPath: String,
        relativePathForLog: String
    ): String {
        var lastErr: Throwable? = null

        repeat(HASH_MAX_ATTEMPTS) { attemptIdx ->
            val attempt = attemptIdx + 1
            try {
                GraphClient.downloadFileStream(contentPath).use { input ->
                    return sha256Hex(input)
                }
            } catch (t: Throwable) {
                lastErr = t
                val isFinal = attempt == HASH_MAX_ATTEMPTS
                Log.e(
                    TAG,
                    "🔥 Failed hashing OneDrive file $relativePathForLog (attempt $attempt/$HASH_MAX_ATTEMPTS) (contentPath=$contentPath)",
                    t
                )
                if (!isFinal) {
                    delay(HASH_BACKOFF_MS * attempt)
                }
            }
        }

        // Give up: store blank hash so index remains complete, and repair later.
        Log.w(TAG, "⚠️ Giving up hashing for now: $relativePathForLog (stored sha256=\"\") lastErr=${lastErr?.javaClass?.simpleName}")
        return ""
    }

    /**
     * Try to repair a few cached entries with blank sha256.
     * This fixes “scar” files from transient network failures even when delta is “no changes”.
     */
    private suspend fun tryRepairBlankHashes(
        map: LinkedHashMap<String, FileHashEntry>,
        maxToRepair: Int
    ): Int {
        if (maxToRepair <= 0) return 0

        val targets = map.values
            .asSequence()
            .filter { it.sha256.isBlank() }
            .take(maxToRepair)
            .toList()

        if (targets.isEmpty()) return 0

        var repaired = 0
        for (e in targets) {
            val relPath = e.relativePath
            val contentPath = "$MUSIC_ROOT/${relPath.trimStart('/')}:/content"

            val sha = hashOneDriveFileWithRetry(
                contentPath = contentPath,
                relativePathForLog = relPath
            )

            if (sha.isNotBlank()) {
                map[relPath] = e.copy(sha256 = sha)
                repaired++
                Log.w(TAG, "✅ Repaired blank hash: $relPath")
            }
        }

        if (repaired > 0) {
            Log.w(TAG, "✅ OneDrive repair pass: repaired=$repaired (requestedMax=$maxToRepair)")
        }

        return repaired
    }

    // ============================================================
    // CACHE IO
    // ============================================================

    private fun readCache(file: File): Map<String, FileHashEntry> {
        return try {
            val arr = JSONArray(file.readText())
            val map = LinkedHashMap<String, FileHashEntry>()

            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rel = o.optString("relativePath", "").trim()
                val sha = o.optString("sha256", "").trim() // may be blank (allowed)
                val size = o.optLong("sizeBytes", -1L)

                if (rel.isBlank()) continue

                map[rel] = FileHashEntry(
                    relativePath = rel,
                    sha256 = sha,
                    sizeBytes = size
                )
            }

            map
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read OneDrive cache", e)
            emptyMap()
        }
    }

    private fun writeCache(file: File, map: Map<String, FileHashEntry>) {
        val arr = JSONArray()
        map.values.forEach { entry ->
            arr.put(
                JSONObject()
                    .put("relativePath", entry.relativePath)
                    .put("sha256", entry.sha256) // may be blank
                    .put("sizeBytes", entry.sizeBytes)
            )
        }
        file.writeText(arr.toString())
    }

    // ============================================================
    // SHA-256
    // ============================================================

    private fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
