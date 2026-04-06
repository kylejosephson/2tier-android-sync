package com.example.kylesmusicplayerandroid.data.artwork

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object AlbumArtRepository {

    private const val TAG = "AlbumArtRepository"

    // NEW: Our stable, album-only cache folder (you can keep forever)
    private const val ALBUM_CACHE_DIR = "artwork_albums"

    // LEGACY: Existing per-song cache folder:
    // /data/data/<pkg>/files/cache/artwork
    private const val LEGACY_ARTWORK_DIR_REL = "cache/artwork"

    // In-memory memoization (hits only; ConcurrentHashMap does NOT allow null values)
    private val memoryHits = ConcurrentHashMap<String, File>()

    // Negative cache (known misses)
    private val memoryMisses = ConcurrentHashMap.newKeySet<String>()

    // ----------------------------
    // Keys (NEW + legacy fallback)
    // ----------------------------

    private fun albumKeyFromAlbumId(albumId: Long): String = "albumId:$albumId"

    // Legacy (fallback only)
    private fun albumKeyFromArtistAlbum(artist: String, album: String): String =
        normalize(artist) + "||" + normalize(album)

    private fun normalize(s: String): String =
        s.trim().lowercase()

    private fun albumCacheFile(ctx: Context, key: String): File {
        val dir = File(ctx.filesDir, ALBUM_CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()

        val hash = sha256Hex(key).take(16)
        return File(dir, "$hash.jpg")
    }

    /**
     * Backwards-compatible API:
     * If representativeSong.albumId > 0, we use stable album-id key.
     * Else we fallback to legacy artist||album key (so you don't break callers today).
     */
    suspend fun resolveAlbumArtFile(
        ctx: Context,
        artist: String,
        album: String,
        representativeSong: RepresentativeSong?
    ): File? = withContext(Dispatchers.IO) {

        val key = if (representativeSong != null && representativeSong.albumId > 0L) {
            albumKeyFromAlbumId(representativeSong.albumId)
        } else {
            albumKeyFromArtistAlbum(artist, album)
        }

        // 0) Fast memory hit
        memoryHits[key]?.let { return@withContext it }

        // 1) Disk cache (IMPORTANT: check disk before honoring miss,
        //    because cache might appear after a previous failure.)
        val target = albumCacheFile(ctx, key)
        if (target.exists() && target.length() > 0) {
            memoryHits[key] = target
            memoryMisses.remove(key)
            return@withContext target
        }

        // 2) If previously known-miss and disk still empty, return null
        if (memoryMisses.contains(key)) return@withContext null

        // 3) Try embedded art from representative song (robust extraction)
        val embedded = representativeSong?.let { extractEmbeddedArtBytes(ctx, it.contentUri) }
        if (embedded != null && embedded.isNotEmpty()) {
            val ok = writeBytes(target, embedded)
            if (ok) {
                memoryHits[key] = target
                memoryMisses.remove(key)
                return@withContext target
            }
        }

        // 4) Try legacy cache for representative song
        val legacy = representativeSong?.let { findLegacySongArtworkFile(ctx, it) }
        if (legacy != null && legacy.exists() && legacy.length() > 0) {
            try {
                legacy.copyTo(target, overwrite = true)
                memoryHits[key] = target
                memoryMisses.remove(key)
                return@withContext target
            } catch (e: Exception) {
                Log.w(TAG, "Failed copying legacy art to album cache: ${e.message}")
            }
        }

        // 5) Nothing (negative-cache it)
        memoryMisses.add(key)
        return@withContext null
    }

    /**
     * Load a Bitmap from the resolved album art file (or null).
     * This decodes at a modest size so scrolling lists stay smooth.
     */
    suspend fun loadAlbumArtBitmap(
        ctx: Context,
        artist: String,
        album: String,
        representativeSong: RepresentativeSong?,
        maxSizePx: Int = 256
    ): Bitmap? = withContext(Dispatchers.IO) {
        val file = resolveAlbumArtFile(ctx, artist, album, representativeSong) ?: return@withContext null
        decodeDownsampled(file, maxSizePx)
    }

    // ----------------------------
    // Embedded extraction (robust)
    // ----------------------------

    /**
     * IMPORTANT: match the working order from AlbumArtCacheBuilder:
     * For content:// URIs try FileDescriptor FIRST, then ctx+uri fallback.
     */
    private fun extractEmbeddedArtBytes(ctx: Context, uriStr: String): ByteArray? {
        val resolver: ContentResolver = ctx.contentResolver
        var retriever: MediaMetadataRetriever? = null

        return try {
            val uri = Uri.parse(uriStr)
            retriever = MediaMetadataRetriever()

            if (uri.scheme == null) {
                // raw path string
                retriever.setDataSource(uriStr)
                retriever.embeddedPicture
            } else if (uri.scheme.equals("file", ignoreCase = true)) {
                val path = uri.path
                if (!path.isNullOrBlank()) {
                    retriever.setDataSource(path)
                    retriever.embeddedPicture
                } else {
                    null
                }
            } else {
                // content:// (or other schemes)

                // 1) FD first (most reliable on many devices)
                val fdBytes = try {
                    resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                        retriever.embeddedPicture
                    }
                } catch (_: Exception) {
                    null
                }

                if (fdBytes != null && fdBytes.isNotEmpty()) {
                    fdBytes
                } else {
                    // 2) ctx+uri fallback (some devices prefer this)
                    val ctxBytes = try {
                        retriever.setDataSource(ctx, uri)
                        retriever.embeddedPicture
                    } catch (_: Exception) {
                        null
                    }

                    if (ctxBytes != null && ctxBytes.isNotEmpty()) ctxBytes else null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Embedded art extract failed for $uriStr : ${e.message}")
            null
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {
            }
        }
    }

    // ----------------------------
    // Legacy file lookup
    // ----------------------------

    private fun findLegacySongArtworkFile(ctx: Context, song: RepresentativeSong): File? {
        val legacyDir = File(ctx.filesDir, LEGACY_ARTWORK_DIR_REL)
        if (!legacyDir.exists() || !legacyDir.isDirectory) return null

        val track2 = song.trackNumber.coerceAtLeast(0).toString().padStart(2, '0')

        // Common candidate patterns:
        val candidates = listOf(
            "$track2 - ${song.title}.jpg",
            "${song.trackNumber} - ${song.title}.jpg"
        ).map { File(legacyDir, sanitizeFilename(it)) }

        for (f in candidates) {
            if (f.exists() && f.length() > 0) return f
        }

        // Best-effort scan: find a file that starts with "01 - " and contains the title (normalized)
        val titleNorm = normalize(song.title).replace(Regex("\\s+"), " ")
        val prefix = "$track2 - "

        val files = legacyDir.listFiles() ?: return null
        val match = files.firstOrNull { file ->
            val nameNorm = normalize(file.nameWithoutExtension).replace(Regex("\\s+"), " ")
            nameNorm.startsWith(normalize(prefix)) && nameNorm.contains(titleNorm)
        }
        return match
    }

    private fun sanitizeFilename(name: String): String {
        return name
            .replace('\\', ' ')
            .replace('/', ' ')
            .replace(':', ' ')
            .replace('*', ' ')
            .replace('?', ' ')
            .replace('"', ' ')
            .replace('<', ' ')
            .replace('>', ' ')
            .replace('|', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ----------------------------
    // File / bitmap helpers
    // ----------------------------

    private fun writeBytes(target: File, bytes: ByteArray): Boolean {
        return try {
            val parent = target.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            FileOutputStream(target).use { it.write(bytes) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing album art cache: ${e.message}")
            false
        }
    }

    private fun decodeDownsampled(file: File, maxSizePx: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)

            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= 0 || h <= 0) return null

            var sample = 1
            while ((w / sample) > maxSizePx || (h / sample) > maxSizePx) {
                sample *= 2
            }

            val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts2)
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    data class RepresentativeSong(
        val title: String,
        val trackNumber: Int,
        val contentUri: String,
        val albumId: Long = -1L // default keeps old call sites working
    )
}
