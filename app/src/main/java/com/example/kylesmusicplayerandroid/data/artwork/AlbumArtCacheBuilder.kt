package com.example.kylesmusicplayerandroid.data.artwork

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.kylesmusicplayerandroid.data.mediastore.MediaStoreSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object AlbumArtCacheBuilder {

    private const val TAG = "AlbumArtCacheBuilder"

    // NEW stable cache
    private const val ALBUM_CACHE_DIR = "artwork_albums"

    // LEGACY cache locations (we will probe several)
    private const val LEGACY_ARTWORK_DIR_REL_FILES = "cache/artwork" // under filesDir
    private const val LEGACY_ARTWORK_DIR_REL_CACHE = "artwork"       // under cacheDir

    // Keep logs useful, not insane
    private const val LOG_EMBED_FAILS = true
    private const val LOG_LEGACY_PROBE = true

    data class Result(
        val totalAlbums: Int,
        val alreadyCached: Int,
        val builtFromEmbedded: Int,
        val builtFromLegacy: Int,
        val missing: Int
    )

    suspend fun buildAllAlbumArtCache(ctx: Context, songs: List<MediaStoreSong>): Result =
        withContext(Dispatchers.IO) {

            // ✅ Prefer stable ALBUM_ID grouping; fallback to legacy artist||album if albumId not present/invalid.
            val grouped = songs
                .filter { it.album.isNotBlank() } // album name should exist
                .groupBy { albumKeyForSong(it) }

            var already = 0
            var fromEmbedded = 0
            var fromLegacy = 0
            var missing = 0

            val total = grouped.size

            val legacyDirs = legacyArtworkDirs(ctx).filter { it.exists() && it.isDirectory }
            if (LOG_LEGACY_PROBE) {
                Log.w(TAG, "Legacy dirs probed:")
                for (d in legacyArtworkDirs(ctx)) {
                    Log.w(TAG, " - ${d.absolutePath}  exists=${d.exists()} dir=${d.isDirectory}")
                }
            }

            Log.w(TAG, "Starting album-art cache build. Albums=$total")

            for ((key, albumSongs) in grouped) {

                val any = albumSongs.first()
                val artist = any.artist
                val album = any.album

                val outFile = albumCacheFile(ctx, key)
                if (outFile.exists() && outFile.length() > 0) {
                    already++
                    continue
                }

                // sort candidates: trackNumber first, then title
                val candidates = albumSongs.sortedWith(
                    compareBy<MediaStoreSong> { it.trackNumber }.thenBy { it.title.lowercase() }
                )

                // 1) embedded art
                val embeddedBytes = findEmbeddedArtBytes(ctx, candidates, artist, album)
                if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
                    if (writeBytes(outFile, embeddedBytes)) {
                        fromEmbedded++
                        Log.w(TAG, "Cached (embedded): $artist / $album  -> ${outFile.name}")
                        continue
                    }
                }

                // 2) legacy cache (try multiple dirs)
                val legacyFile = findLegacyArtworkFile(ctx, legacyDirs, candidates)
                if (legacyFile != null && legacyFile.exists() && legacyFile.length() > 0) {
                    try {
                        legacyFile.copyTo(outFile, overwrite = true)
                        fromLegacy++
                        Log.w(TAG, "Cached (legacy):   $artist / $album  -> ${outFile.name}")
                        continue
                    } catch (e: Exception) {
                        Log.w(TAG, "Copy legacy -> album cache failed for $artist/$album : ${e.message}")
                    }
                }

                missing++
                Log.w(TAG, "MISSING ART:       $artist / $album")
            }

            val result = Result(
                totalAlbums = total,
                alreadyCached = already,
                builtFromEmbedded = fromEmbedded,
                builtFromLegacy = fromLegacy,
                missing = missing
            )

            Log.w(TAG, "Album-art cache build complete: $result")
            result
        }

    // ----------------------------
    // Key + cache path
    // ----------------------------

    private fun albumKeyForSong(song: MediaStoreSong): String {
        return if (song.albumId > 0L) {
            // ✅ Stable key
            "albumId:${song.albumId}"
        } else {
            // Fallback key (legacy behavior)
            normalize(song.artist) + "||" + normalize(song.album)
        }
    }

    private fun normalize(s: String): String =
        s.trim().lowercase()

    private fun albumCacheFile(ctx: Context, albumKey: String): File {
        val dir = File(ctx.filesDir, ALBUM_CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        val hash = sha256Hex(albumKey).take(16)
        return File(dir, "$hash.jpg")
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    // ----------------------------
    // Embedded art extraction
    // ----------------------------

    private fun findEmbeddedArtBytes(
        ctx: Context,
        candidates: List<MediaStoreSong>,
        artist: String,
        album: String
    ): ByteArray? {
        for (song in candidates) {
            val (bytes, err) = extractEmbeddedArtBytesWithError(ctx, song.contentUri)
            if (bytes != null && bytes.isNotEmpty()) return bytes

            if (LOG_EMBED_FAILS && err != null) {
                Log.w(TAG, "Embedded fail sample for album: $artist / $album  uri=${song.contentUri}  err=$err")
                return null
            }
        }
        return null
    }

    private fun extractEmbeddedArtBytesWithError(ctx: Context, uriStr: String): Pair<ByteArray?, String?> {
        val resolver: ContentResolver = ctx.contentResolver
        var retriever: MediaMetadataRetriever? = null

        return try {
            val uri = Uri.parse(uriStr)
            retriever = MediaMetadataRetriever()

            if (uri.scheme == null) {
                retriever.setDataSource(uriStr)
                Pair(retriever.embeddedPicture, null)
            } else if (uri.scheme.equals("file", ignoreCase = true)) {
                val path = uri.path
                if (!path.isNullOrBlank()) {
                    retriever.setDataSource(path)
                    Pair(retriever.embeddedPicture, null)
                } else {
                    Pair(null, "file:// uri has blank path")
                }
            } else {
                val fdBytes = try {
                    resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                        retriever.embeddedPicture
                    }
                } catch (_: Exception) {
                    null
                }

                if (fdBytes != null && fdBytes.isNotEmpty()) {
                    Pair(fdBytes, null)
                } else {
                    val ctxBytes = try {
                        retriever.setDataSource(ctx, uri)
                        retriever.embeddedPicture
                    } catch (_: Exception) {
                        null
                    }

                    if (ctxBytes != null && ctxBytes.isNotEmpty()) {
                        Pair(ctxBytes, null)
                    } else {
                        Pair(null, "No embeddedPicture (FD + ctx/uri both empty)")
                    }
                }
            }
        } catch (e: Exception) {
            Pair(null, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                retriever?.release()
            } catch (_: Exception) {}
        }
    }

    // ----------------------------
    // Legacy cache lookup
    // ----------------------------

    private fun legacyArtworkDirs(ctx: Context): List<File> {
        return listOf(
            File(ctx.filesDir, LEGACY_ARTWORK_DIR_REL_FILES),
            File(ctx.cacheDir, LEGACY_ARTWORK_DIR_REL_CACHE),
            File(ctx.cacheDir, LEGACY_ARTWORK_DIR_REL_FILES)
        )
    }

    private fun findLegacyArtworkFile(
        ctx: Context,
        legacyDirs: List<File>,
        candidates: List<MediaStoreSong>
    ): File? {

        if (legacyDirs.isEmpty()) return null

        for (song in candidates) {
            val track2 = song.trackNumber.coerceAtLeast(0).toString().padStart(2, '0')
            val name1 = sanitizeFilename("$track2 - ${song.title}.jpg")
            val name2 = sanitizeFilename("${song.trackNumber} - ${song.title}.jpg")

            for (dir in legacyDirs) {
                val exact1 = File(dir, name1)
                if (exact1.exists() && exact1.length() > 0) return exact1

                val exact2 = File(dir, name2)
                if (exact2.exists() && exact2.length() > 0) return exact2
            }
        }

        val songTitlesNorm = candidates.map { normalize(it.title) }.toSet()

        for (dir in legacyDirs) {
            val files = dir.listFiles() ?: continue
            val best = files.firstOrNull { f ->
                val name = normalize(f.nameWithoutExtension)
                name.startsWith("01 -") && songTitlesNorm.any { t -> name.contains(t) }
            }
            if (best != null) return best
        }

        return null
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

    private fun writeBytes(target: File, bytes: ByteArray): Boolean {
        return try {
            val parent = target.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            FileOutputStream(target).use { it.write(bytes) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Write album cache failed: ${e.message}")
            false
        }
    }
}
