package com.example.kylesmusicplayerandroid.data.mediastore

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object MediaStoreCache {

    private const val FILE_NAME = "media_store_songs.json"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun load(context: Context): List<MediaStoreSong>? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null

        return try {
            json.decodeFromString(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun save(context: Context, songs: List<MediaStoreSong>) {
        val file = File(context.filesDir, FILE_NAME)
        file.writeText(json.encodeToString(songs))
    }

    /**
     * Deletes the cached MediaStore song list so the next load will rescan MediaStore.
     * This is UI-only caching; it does not affect sync/index logic.
     */
    fun clear(context: Context): Boolean {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.delete() else true
        } catch (_: Throwable) {
            false
        }
    }
}
