package com.example.kylesmusicplayerandroid.data.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.min
import kotlin.random.Random

data class FileHashEntry(
    val relativePath: String,
    val sha256: String,          // may be blank if unknown/failed
    val sizeBytes: Long,
    val lastModified: Long       // millis; -1 if unknown
)

class SdCardIndex private constructor(
    val entriesByPath: Map<String, FileHashEntry>,
    val fileCount: Int,
    val isCacheTrusted: Boolean
) {

    companion object {
        private const val TAG = "SdCardIndex"
        private const val PREFS_NAME = "app_prefs"
        private const val MUSIC_URI_KEY = "music_uri"
        private const val CACHE_FILE = "sdcard_index.json"

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "aac", "m4a", "ogg", "wma"
        )

        // Cache meta JSON keys
        private const val META_KEY = "meta"
        private const val ENTRIES_KEY = "entries"
        private const val META_TREE_URI = "musicTreeUri"
        private const val META_ENTRY_COUNT = "entryCount"
        private const val META_TOTAL_SIZE = "totalSizeBytes"
        private const val META_SUM_LASTMOD = "sumLastModified"
        private const val META_CREATED_AT = "createdAtEpochMs"

        // Gate defaults (simple “is it real?” checks)
        private const val DEFAULT_MIN_CACHE_BYTES = 256L
        private const val DEFAULT_MIN_CACHE_ENTRIES = 10

        data class CacheMeta(
            val musicTreeUri: String,
            val entryCount: Int,
            val totalSizeBytes: Long,
            val sumLastModified: Long,
            val createdAtEpochMs: Long
        )

        data class CacheData(
            val root: DocumentFile,
            val cacheMap: MutableMap<String, FileHashEntry>,
            val meta: CacheMeta?
        )

        /**
         * Progress callback for gate screens:
         * - stage: "Scanning folders", "Hashing", "Writing cache", etc.
         * - indexedCount: how many audio entries processed
         * - reused/rehashed/failed: counters (nice for debugging + user confidence)
         */
        data class BuildProgress(
            val stage: String,
            val indexedCount: Int,
            val reused: Int,
            val rehashed: Int,
            val failed: Int
        )

        // ============================================================
        // PUBLIC API (NEW): Gate-friendly cache ensure
        // ============================================================

        /**
         * OPTION B support:
         * Ensure sdcard_index.json exists and is "big enough" to be considered real.
         *
         * - If cache is missing/too small OR forceRebuild=true => do a full build (slow).
         * - Otherwise => no-op.
         *
         * NOTE: This does NOT verify trusted-ness; it just ensures the cache exists.
         * Trust verification remains in buildFromCacheOnly().
         */
        fun ensureCacheBuilt(
            context: Context,
            forceRebuild: Boolean = false,
            minCacheBytes: Long = DEFAULT_MIN_CACHE_BYTES,
            minCacheEntries: Int = DEFAULT_MIN_CACHE_ENTRIES,
            onProgress: ((BuildProgress) -> Unit)? = null
        ): Boolean {

            val cacheFile = File(context.filesDir, CACHE_FILE)
            val cacheBytes = if (cacheFile.exists()) cacheFile.length() else 0L

            if (!forceRebuild && cacheFile.exists() && cacheBytes >= minCacheBytes) {
                // Extra safety: if it parses and has >= minCacheEntries, we accept it.
                val (meta, map) = readCacheWithMeta(cacheFile)
                if (map.size >= minCacheEntries) {
                    Log.w(TAG, "✅ SD cache already present (${map.size} entries, ${cacheBytes} bytes). Skipping full build.")
                    return true
                }
                Log.w(TAG, "⚠️ SD cache exists but too small/invalid (entries=${map.size}, bytes=$cacheBytes). Rebuilding…")
            } else {
                if (!cacheFile.exists()) {
                    Log.w(TAG, "SD cache missing. Building sdcard_index.json…")
                } else if (forceRebuild) {
                    Log.w(TAG, "Force rebuild requested. Building sdcard_index.json…")
                } else {
                    Log.w(TAG, "SD cache too small (bytes=$cacheBytes < $minCacheBytes). Building sdcard_index.json…")
                }
            }

            // Full build (slow)
            val built = buildWithProgressInternal(context, onProgress)
            return built != null && built.entriesByPath.isNotEmpty()
        }

        // ============================================================
        // PUBLIC API (existing)
        // ============================================================

        /**
         * FAST check-only build:
         * - Loads cache
         * - (optional) verifies cache via small SAF sample
         * - DOES NOT walk the SD tree
         * - DOES NOT hash any files
         */
        fun buildFromCacheOnly(
            context: Context,
            requiredPaths: Set<String>,
            sampleVerifyCount: Int = 25
        ): SdCardIndex? {

            val data = loadRootAndCache(context) ?: return null

            val ordered = requiredPaths
                .asSequence()
                .map { it.trim().trimStart('/') }
                .filter { it.isNotBlank() }
                .filter { isAudioPath(it) }
                .distinct()
                .sorted()
                .toList()

            val outMap = LinkedHashMap<String, FileHashEntry>()
            for (p in ordered) {
                val e = data.cacheMap[p]
                if (e != null) outMap[p] = e
            }

            val trusted = verifyCacheTrusted(
                context = context,
                root = data.root,
                cacheMap = data.cacheMap,
                meta = data.meta,
                sampleVerifyCount = sampleVerifyCount
            )

            if (!trusted) {
                Log.w(TAG, "⚠️ SD cache NOT trusted by fingerprint/sample check. Returning cache-only result anyway to keep UI fast.")
            } else {
                Log.w(TAG, "✅ SD cache trusted (fingerprint/sample). Cache-only build is FAST.")
            }

            return SdCardIndex(
                entriesByPath = outMap,
                fileCount = outMap.size,
                isCacheTrusted = trusted
            )
        }

        /**
         * Merge new/updated entries (e.g. after Sync Songs downloads),
         * then write the cache ONCE (atomic) with updated fingerprint meta.
         *
         * - No SD scanning
         * - No resolveFile() per entry
         * - Designed for "downloaded files only"
         */
        fun mergeDownloadedEntries(
            context: Context,
            updates: Map<String, FileHashEntry>
        ) {
            if (updates.isEmpty()) return

            val data = loadRootAndCache(context) ?: return
            val root = data.root
            val cache = data.cacheMap

            var applied = 0
            for ((k, v) in updates) {
                val path = k.trim().trimStart('/')
                if (path.isBlank()) continue
                if (!isAudioPath(path)) continue
                cache[path] = v.copy(relativePath = path)
                applied++
            }

            if (applied <= 0) return

            writeCacheAtomic(
                context = context,
                musicTreeUri = root.uri.toString(),
                map = cache
            )

            Log.w(TAG, "✅ SD cache merged/updated: applied=$applied totalNow=${cache.size}")
        }

        /**
         * Legacy build: full SD scan (slow).
         * Kept so existing callers don't break.
         */
        fun build(context: Context): SdCardIndex? {
            return buildWithProgressInternal(context, onProgress = null)
        }

        // ============================================================
        // FULL BUILD (slow) with progress (used by Option B gate)
        // ============================================================

        private fun buildWithProgressInternal(
            context: Context,
            onProgress: ((BuildProgress) -> Unit)?
        ): SdCardIndex? {

            val data = loadRootAndCache(context) ?: return null
            val root = data.root
            val cachedByPath = data.cacheMap
            val resolver = context.contentResolver

            val map = LinkedHashMap<String, FileHashEntry>()
            var count = 0
            var reused = 0
            var rehashed = 0
            var failed = 0

            fun emit(stage: String) {
                onProgress?.invoke(
                    BuildProgress(
                        stage = stage,
                        indexedCount = count,
                        reused = reused,
                        rehashed = rehashed,
                        failed = failed
                    )
                )
            }

            emit("Scanning SD card…")

            fun walk(dir: DocumentFile, relDir: String) {
                val children = try {
                    dir.listFiles()
                } catch (t: Throwable) {
                    Log.e(TAG, "🔥 Failed to listFiles() at relDir='$relDir' uri=${dir.uri}", t)
                    return
                }

                for (child in children) {

                    if (child.isDirectory) {
                        val dirName = child.name?.trim().orEmpty()
                        if (dirName.isBlank()) continue

                        val nextRelDir = if (relDir.isBlank()) dirName else "$relDir/$dirName"
                        walk(child, nextRelDir)
                        continue
                    }

                    val fileName = child.name?.trim().orEmpty()
                    if (fileName.isBlank()) continue

                    val ext = fileName.substringAfterLast('.', "").lowercase()
                    if (ext !in AUDIO_EXTENSIONS) continue

                    val relativePath =
                        if (relDir.isBlank()) fileName else "$relDir/$fileName"

                    val sizeBytes = try { child.length() } catch (_: Throwable) { -1L }
                    val lastModified = try { child.lastModified() } catch (_: Throwable) { -1L }

                    val cached = cachedByPath[relativePath]
                    val sha = computeOrReuseHash(
                        resolverOpen = { resolver.openInputStream(child.uri) },
                        path = relativePath,
                        uri = child.uri.toString(),
                        sizeBytes = sizeBytes,
                        lastModified = lastModified,
                        cached = cached,
                        onReused = { reused++ },
                        onRehashed = { rehashed++ },
                        onFailed = { failed++ }
                    )

                    val entry = FileHashEntry(
                        relativePath = relativePath,
                        sha256 = sha,
                        sizeBytes = sizeBytes,
                        lastModified = lastModified
                    )

                    map[relativePath] = entry

                    count++
                    if (count % 100 == 0) {
                        emit("Indexing / hashing… ($count songs)")
                    }
                    if (count % 250 == 0) {
                        Log.w(TAG, "Indexed $count SD files… (reused=$reused rehashed=$rehashed failed=$failed)")
                    }
                }
            }

            walk(root, "")

            emit("Writing sdcard_index.json…")
            writeCacheAtomic(context, root.uri.toString(), map)

            emit("Done.")

            Log.w(TAG, "✅ SD index ready (full): total=$count reused=$reused rehashed=$rehashed failed=$failed")

            return SdCardIndex(entriesByPath = map, fileCount = count, isCacheTrusted = true)
        }

        // ============================================================
        // ROOT + CACHE LOADING (includes auto-migration)
        // ============================================================

        private fun loadRootAndCache(context: Context): CacheData? {

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val uriString = prefs.getString(MUSIC_URI_KEY, null)

            if (uriString.isNullOrBlank()) {
                Log.e(TAG, "🔥 Music SAF URI missing (prefs key=$MUSIC_URI_KEY)")
                return null
            }

            val rootUri = Uri.parse(uriString)
            val root = DocumentFile.fromTreeUri(context, rootUri)
            if (root == null) {
                Log.e(TAG, "🔥 DocumentFile.fromTreeUri returned null for URI: $rootUri")
                return null
            }

            val cacheFile = File(context.filesDir, CACHE_FILE)
            val (meta, map) = if (cacheFile.exists()) {
                readCacheWithMeta(cacheFile).let { it.first to it.second.toMutableMap() }
            } else {
                null to mutableMapOf()
            }

            if (map.isNotEmpty()) {
                Log.w(TAG, "Loaded ${map.size} SD audio files from cache")
            }

            // Auto-migrate old array format to new meta format WITHOUT scanning SD
            if (map.isNotEmpty() && meta == null) {
                Log.w(TAG, "Cache meta missing (old cache format). Auto-migrating to meta+entries format (no SD scan).")
                writeCacheAtomic(context, root.uri.toString(), map)
                // Reload to get meta
                val reread = readCacheWithMeta(File(context.filesDir, CACHE_FILE))
                return CacheData(root = root, cacheMap = reread.second.toMutableMap(), meta = reread.first)
            }

            return CacheData(root = root, cacheMap = map, meta = meta)
        }

        // ============================================================
        // CACHE TRUST (fingerprint + sample)
        // ============================================================

        private fun verifyCacheTrusted(
            context: Context,
            root: DocumentFile,
            cacheMap: Map<String, FileHashEntry>,
            meta: CacheMeta?,
            sampleVerifyCount: Int
        ): Boolean {

            if (cacheMap.isEmpty()) return false
            if (meta == null) return false

            val currentTree = root.uri.toString()
            if (meta.musicTreeUri != currentTree) {
                Log.w(TAG, "Cache treeUri mismatch. meta=${meta.musicTreeUri} current=$currentTree")
                return false
            }

            if (meta.entryCount != cacheMap.size) {
                Log.w(TAG, "Cache entryCount mismatch. meta=${meta.entryCount} actual=${cacheMap.size}")
                return false
            }

            // Recompute fingerprint from cache contents (no SAF)
            var totalSize = 0L
            var sumLastMod = 0L
            for (e in cacheMap.values) {
                if (e.sizeBytes > 0) totalSize += e.sizeBytes
                if (e.lastModified > 0) sumLastMod += e.lastModified
            }

            if (meta.totalSizeBytes != totalSize) {
                Log.w(TAG, "Cache totalSizeBytes mismatch. meta=${meta.totalSizeBytes} computed=$totalSize")
                return false
            }

            if (meta.sumLastModified != sumLastMod) {
                Log.w(TAG, "Cache sumLastModified mismatch. meta=${meta.sumLastModified} computed=$sumLastMod")
                return false
            }

            // Bounded SAF sampling
            val n = min(sampleVerifyCount, cacheMap.size)
            if (n <= 0) return true

            val keys = cacheMap.keys.toList()
            val rng = Random(0x51DCAFE) // stable sample across runs
            repeat(n) {
                val path = keys[rng.nextInt(keys.size)]
                val cached = cacheMap[path] ?: return@repeat

                val doc = resolveFile(root, path)
                if (doc == null) {
                    Log.w(TAG, "Sample verify failed: missing on disk: $path")
                    return false
                }

                val sizeNow = try { doc.length() } catch (_: Throwable) { -1L }
                if (cached.sizeBytes > 0 && sizeNow > 0 && cached.sizeBytes != sizeNow) {
                    Log.w(TAG, "Sample verify failed: size mismatch: $path cached=${cached.sizeBytes} now=$sizeNow")
                    return false
                }

                val lmNow = try { doc.lastModified() } catch (_: Throwable) { -1L }
                if (cached.lastModified > 0 && lmNow > 0 && cached.lastModified != lmNow) {
                    Log.w(TAG, "Sample verify failed: lastModified mismatch: $path cached=${cached.lastModified} now=$lmNow")
                    return false
                }
            }

            return true
        }

        // ============================================================
        // TARGETED FILE RESOLUTION (sampling only; keep it bounded)
        // ============================================================

        private fun isAudioPath(path: String): Boolean {
            val name = path.substringAfterLast('/', path).trim()
            if (name.isBlank()) return false
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in AUDIO_EXTENSIONS
        }

        private fun resolveFile(rootDir: DocumentFile, relativePath: String): DocumentFile? {
            val cleaned = relativePath.trim().trimStart('/')
            if (cleaned.isBlank()) return null

            val parts = cleaned.split("/").filter { it.isNotBlank() }
            if (parts.isEmpty()) return null

            var current: DocumentFile? = rootDir
            for (i in 0 until parts.lastIndex) {
                val folder = parts[i]
                current = current?.findFile(folder)
                if (current == null || !current.isDirectory) return null
            }

            val filename = parts.last()
            val file = current?.findFile(filename)
            if (file == null || !file.isFile) return null

            return file
        }

        // ============================================================
        // HASH REUSE POLICY (used only by full build)
        // ============================================================

        private fun computeOrReuseHash(
            resolverOpen: () -> InputStream?,
            path: String,
            uri: String,
            sizeBytes: Long,
            lastModified: Long,
            cached: FileHashEntry?,
            onReused: () -> Unit,
            onRehashed: () -> Unit,
            onFailed: () -> Unit
        ): String {

            val cachedHashUsable = cached != null && cached.sha256.isNotBlank()

            val canTrustLastModified =
                lastModified > 0L && (cached?.lastModified ?: -1L) > 0L

            val unchanged = if (cached == null) {
                false
            } else if (canTrustLastModified) {
                cached.sizeBytes == sizeBytes && cached.lastModified == lastModified
            } else {
                cached.sizeBytes == sizeBytes
            }

            if (unchanged && cachedHashUsable) {
                onReused()
                return cached!!.sha256
            }

            return try {
                val stream = resolverOpen()
                if (stream == null) {
                    onFailed()
                    Log.e(TAG, "🔥 openInputStream() returned null for $path uri=$uri")
                    ""
                } else {
                    onRehashed()
                    stream.use { sha256Hex(it) }
                }
            } catch (t: Throwable) {
                onFailed()
                Log.e(TAG, "🔥 Failed hashing $path uri=$uri", t)
                ""
            }
        }

        // ============================================================
        // CACHE IO
        // ============================================================

        private fun readCacheWithMeta(file: File): Pair<CacheMeta?, Map<String, FileHashEntry>> {
            return try {
                val text = file.readText().trim()
                if (text.isBlank()) return Pair(null, emptyMap())

                // Old format: JSON array
                if (text.startsWith("[")) {
                    val arr = JSONArray(text)
                    val map = LinkedHashMap<String, FileHashEntry>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val rel = o.optString("relativePath", "").trim()
                        if (rel.isBlank()) continue
                        map[rel] = FileHashEntry(
                            relativePath = rel,
                            sha256 = o.optString("sha256", "").trim(),
                            sizeBytes = o.optLong("sizeBytes", -1L),
                            lastModified = o.optLong("lastModified", -1L)
                        )
                    }
                    return Pair(null, map)
                }

                // New format: JSON object { meta, entries: [] }
                val obj = JSONObject(text)
                val metaObj = obj.optJSONObject(META_KEY)
                val meta = if (metaObj != null) {
                    CacheMeta(
                        musicTreeUri = metaObj.optString(META_TREE_URI, ""),
                        entryCount = metaObj.optInt(META_ENTRY_COUNT, 0),
                        totalSizeBytes = metaObj.optLong(META_TOTAL_SIZE, 0L),
                        sumLastModified = metaObj.optLong(META_SUM_LASTMOD, 0L),
                        createdAtEpochMs = metaObj.optLong(META_CREATED_AT, 0L)
                    )
                } else null

                val arr = obj.optJSONArray(ENTRIES_KEY) ?: JSONArray()
                val map = LinkedHashMap<String, FileHashEntry>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val rel = o.optString("relativePath", "").trim()
                    if (rel.isBlank()) continue
                    map[rel] = FileHashEntry(
                        relativePath = rel,
                        sha256 = o.optString("sha256", "").trim(),
                        sizeBytes = o.optLong("sizeBytes", -1L),
                        lastModified = o.optLong("lastModified", -1L)
                    )
                }

                Pair(meta, map)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read SD cache", e)
                Pair(null, emptyMap())
            }
        }

        private fun writeCacheAtomic(context: Context, musicTreeUri: String, map: Map<String, FileHashEntry>) {
            val file = File(context.filesDir, CACHE_FILE)
            val tmp = File(context.filesDir, "$CACHE_FILE.tmp")

            val obj = buildCacheObject(musicTreeUri, map)
            tmp.writeText(obj.toString())

            if (file.exists()) file.delete()
            val ok = tmp.renameTo(file)
            if (!ok) {
                // best-effort fallback
                file.writeText(obj.toString())
                try { tmp.delete() } catch (_: Throwable) {}
            }
        }

        private fun buildCacheObject(musicTreeUri: String, map: Map<String, FileHashEntry>): JSONObject {
            var totalSize = 0L
            var sumLastMod = 0L
            for (e in map.values) {
                if (e.sizeBytes > 0) totalSize += e.sizeBytes
                if (e.lastModified > 0) sumLastMod += e.lastModified
            }

            val meta = JSONObject()
                .put(META_TREE_URI, musicTreeUri)
                .put(META_ENTRY_COUNT, map.size)
                .put(META_TOTAL_SIZE, totalSize)
                .put(META_SUM_LASTMOD, sumLastMod)
                .put(META_CREATED_AT, System.currentTimeMillis())

            val arr = JSONArray()
            map.values.forEach { entry ->
                arr.put(
                    JSONObject()
                        .put("relativePath", entry.relativePath)
                        .put("sha256", entry.sha256)
                        .put("sizeBytes", entry.sizeBytes)
                        .put("lastModified", entry.lastModified)
                )
            }

            return JSONObject()
                .put(META_KEY, meta)
                .put(ENTRIES_KEY, arr)
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

    fun containsPath(relativePath: String): Boolean =
        entriesByPath.containsKey(relativePath)

    fun get(relativePath: String): FileHashEntry? =
        entriesByPath[relativePath]
}
