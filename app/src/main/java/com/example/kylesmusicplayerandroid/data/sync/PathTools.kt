package com.example.kylesmusicplayerandroid.data.sync

import com.example.kylesmusicplayerandroid.data.metadata.SongEntry

/**
 * PathTools
 *
 * Converts SongEntry metadata → clean relative paths
 *
 * Rules:
 *   - Artist folder = albumArtist (fallback: "Unknown Artist")
 *   - Album folder = album (if empty → no album folder)
 *   - Filename = trackNumber + " " + title + ".mp3"
 *   - Track number: if "4/10" → "04", if "4" → "04"
 */
object PathTools {

    /**
     * Convert a SongEntry into a OneDrive and SD card friendly relative path.
     *
     * Produces:
     *   "Artist/Album/01 My Song.mp3"
     * or
     *   "Artist/01 My Song.mp3"
     */
    fun buildRelativePath(entry: SongEntry): String {

        val artist = sanitize(entry.albumArtist)
            .ifBlank { "Unknown Artist" }

        val album = sanitize(entry.album)

        // Normalize track number
        val track = normalizeTrackNumber(entry.trackNumber)

        val title = sanitize(entry.title)
            .ifBlank { "Unknown Title" }

        val filename = "$track $title.mp3"

        return if (album.isBlank()) {
            "$artist/$filename"
        } else {
            "$artist/$album/$filename"
        }
    }


    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    private fun sanitize(text: String): String {
        return text
            .trim()
            .replace("/", "-")
            .replace("\\", "-")
            .replace(":", "-")
            .replace("*", "-")
            .replace("?", "")
            .replace("\"", "'")
            .replace("<", "(")
            .replace(">", ")")
            .replace("|", "-")
    }

    private fun normalizeTrackNumber(num: String): String {
        // examples:
        // "4" → "04"
        // "4/10" → "04"
        val digits = num.takeWhile { it.isDigit() }
        val n = digits.toIntOrNull() ?: 0
        return n.toString().padStart(2, '0')
    }
}