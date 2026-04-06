package com.example.kylesmusicplayerandroid.data.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.collections.iterator

// ------------------------------------------------------------
//  Data class matching desktop rebuild() result
// ------------------------------------------------------------
data class RebuildResult(
    val total: Int,
    val new: Int,
    val removed: Int
)

// ------------------------------------------------------------
//  Full SAF-based metadata manager
// ------------------------------------------------------------
object MusicMetadataManager {

    private const val METADATA_FILE_NAME = "metadata.json"
    private const val ARTWORK_DIR_REL = "cache/artwork"

    private val AUDIO_EXTS = setOf("mp3", "flac", "ogg", "wav", "m4a")


    // ------------------------------------------------------------
    //  SAF-based rebuild
    // ------------------------------------------------------------
    suspend fun rebuildMetadataSaf(
        context: Context,
        root: DocumentFile
    ): RebuildResult {

        val filesDir = context.filesDir
        val metadataFile = File(filesDir, METADATA_FILE_NAME)

        // ---- Load existing metadata into a map ----
        val metadataMap = mutableMapOf<String, JSONObject>()

        if (metadataFile.exists()) {
            try {
                val text = metadataFile.readText()
                if (text.isNotBlank()) {
                    val rootObj = JSONObject(text)
                    val keys = rootObj.keys()
                    while (keys.hasNext()) {
                        val path = keys.next()
                        val obj = rootObj.getJSONObject(path)
                        metadataMap[path] = obj
                    }
                }
            } catch (_: Exception) {
                metadataMap.clear()
            }
        }

        val found = mutableSetOf<String>()
        var newCount = 0

        val artworkDir = File(filesDir, ARTWORK_DIR_REL)
        if (!artworkDir.exists()) artworkDir.mkdirs()

        // ---- SAF traversal ----
        fun walk(doc: DocumentFile) {
            if (doc.isDirectory) {
                doc.listFiles().forEach { child ->
                    walk(child)
                }
            } else if (doc.isFile) {
                val name = doc.name ?: return
                val ext = name.substringAfterLast('.', "").lowercase()

                if (ext in AUDIO_EXTS) {
                    val uriPath = doc.uri.toString()   // SAF path as unique key
                    found.add(uriPath)

                    if (!metadataMap.containsKey(uriPath)) {
                        val tags = extractTagsFromSafFile(context, doc, artworkDir, filesDir)
                        metadataMap[uriPath] = tags
                        newCount++
                    }
                }
            }
        }

        walk(root)

        // ---- Remove metadata for files that no longer exist ----
        val toRemove = metadataMap.keys - found
        var removedCount = 0

        for (dead in toRemove) {
            val obj = metadataMap[dead]
            val artRel = obj?.optString("artwork", "") ?: ""
            if (artRel.isNotEmpty()) {
                val artAbs = File(filesDir, artRel)
                if (artAbs.exists()) {
                    try { artAbs.delete() } catch (_: Exception) {}
                }
            }
            metadataMap.remove(dead)
            removedCount++
        }

        // ---- Write the final JSON ----
        val outRoot = JSONObject()
        for ((path, obj) in metadataMap) {
            outRoot.put(path, obj)
        }
        metadataFile.writeText(outRoot.toString(4))

        return RebuildResult(
            total = metadataMap.size,
            new = newCount,
            removed = removedCount
        )
    }


    // ------------------------------------------------------------
    //  Tag extraction for a SAF DocumentFile
    // ------------------------------------------------------------
    private fun extractTagsFromSafFile(
        context: Context,
        doc: DocumentFile,
        artworkDir: File,
        filesDir: File
    ): JSONObject {

        val retriever = MediaMetadataRetriever()

        return try {
            context.contentResolver.openFileDescriptor(doc.uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
            }

            fun mm(key: Int): String = retriever.extractMetadata(key) ?: ""

            val title = mm(MediaMetadataRetriever.METADATA_KEY_TITLE)
                .ifEmpty { doc.name?.substringBeforeLast('.') ?: "Unknown Title" }

            val albumArtist = mm(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                .ifEmpty { mm(MediaMetadataRetriever.METADATA_KEY_ARTIST) }

            val album = mm(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val publisher = mm(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            val discNumber = mm(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
            val trackNumber = mm(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            val year = mm(MediaMetadataRetriever.METADATA_KEY_YEAR)
            val genre = mm(MediaMetadataRetriever.METADATA_KEY_GENRE)
            val composer = mm(MediaMetadataRetriever.METADATA_KEY_COMPOSER)

            val artworkRel = saveArtworkIfPresent(
                retriever, doc, context, artworkDir, filesDir
            )

            JSONObject().apply {
                put("title", title)
                put("album_artist", albumArtist)
                put("album", album)
                put("publisher", publisher)
                put("disc_number", discNumber)
                put("track_number", trackNumber)
                put("total_discs", "")
                put("year", year)
                put("genre", genre)
                put("composer", composer)
                put("artwork", artworkRel)
            }

        } catch (_: Exception) {
            JSONObject().apply {
                put("title", doc.name ?: "Unknown")
                put("album_artist", "")
                put("album", "")
                put("publisher", "")
                put("disc_number", "")
                put("track_number", "")
                put("total_discs", "")
                put("year", "")
                put("genre", "")
                put("composer", "")
                put("artwork", "")
            }
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }


    // ------------------------------------------------------------
    //  Extract & save embedded artwork
    // ------------------------------------------------------------
    private fun saveArtworkIfPresent(
        retriever: MediaMetadataRetriever,
        doc: DocumentFile,
        context: Context,
        artworkDir: File,
        filesDir: File
    ): String {

        return try {
            val bytes = retriever.embeddedPicture ?: return ""
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""

            if (!artworkDir.exists()) artworkDir.mkdirs()

            val stem = doc.name?.substringBeforeLast('.') ?: "cover"
            val out = File(artworkDir, "$stem.jpg")

            FileOutputStream(out).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }

            // Return relative path (desktop-style)
            out.relativeTo(filesDir).path.replace(File.separatorChar, '/')

        } catch (_: Exception) {
            ""
        }
    }
}
