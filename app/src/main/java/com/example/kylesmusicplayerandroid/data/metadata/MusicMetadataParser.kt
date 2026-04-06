package com.example.kylesmusicplayerandroid.data.metadata

import org.json.JSONObject

object MusicMetadataParser {

    /**
     * Parse the desktop-style JSON into:
     *   Map<String, SongEntry>
     *
     * JSON shape:
     * {
     *    "C:\\Users\\kylej\\Music\\Artist\\Song.mp3": {
     *         "title": "...",
     *         "album_artist": "...",
     *         "album": "...",
     *         "track_number": "...",
     *         "artwork": "cache\\artwork\\image.jpg",
     *         ...
     *    },
     *    ...
     * }
     */
    fun parse(jsonText: String): Map<String, SongEntry> {
        val result = mutableMapOf<String, SongEntry>()

        try {
            val root = JSONObject(jsonText)
            val keys = root.keys()

            while (keys.hasNext()) {
                val path = keys.next()
                val obj = root.getJSONObject(path)

                // Normalize artwork backslashes → forward slashes
                val artwork = obj.optString("artwork", "")
                    .replace("\\", "/")

                val entry = SongEntry(
                    path = path,  // full path from desktop
                    title = obj.optString("title", ""),
                    albumArtist = obj.optString("album_artist", ""),
                    album = obj.optString("album", ""),
                    trackNumber = obj.optString("track_number", ""),
                    artwork = artwork
                )

                result[path] = entry
            }

        } catch (e: Exception) {
            // If corrupted, return empty map.
            return emptyMap()
        }

        return result
    }
}