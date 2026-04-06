package com.example.kylesmusicplayerandroid.data.mediastore

import android.content.Context
import android.provider.MediaStore

object MediaStoreScanner {

    fun scan(context: Context): List<MediaStoreSong> {
        val resolver = context.contentResolver

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.ALBUM_ID,   // ✅ NEW (stable album grouping)
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC}=1"
        val sortOrder =
            "${MediaStore.Audio.Media.ARTIST} ASC, " +
                    "${MediaStore.Audio.Media.ALBUM} ASC, " +
                    "${MediaStore.Audio.Media.TRACK} ASC"

        val songs = mutableListOf<MediaStoreSong>()

        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->

            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)

                songs += MediaStoreSong(
                    mediaStoreId = id,
                    albumId = albumId,
                    title = cursor.getString(titleCol) ?: "",
                    artist = cursor.getString(artistCol) ?: "",
                    album = cursor.getString(albumCol) ?: "",
                    trackNumber = cursor.getInt(trackCol),
                    durationMs = cursor.getLong(durationCol),
                    contentUri = "${MediaStore.Audio.Media.EXTERNAL_CONTENT_URI}/$id"
                )
            }
        }

        return songs
    }
}
