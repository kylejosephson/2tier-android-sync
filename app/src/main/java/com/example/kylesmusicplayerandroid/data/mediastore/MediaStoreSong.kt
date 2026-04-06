package com.example.kylesmusicplayerandroid.data.mediastore

import kotlinx.serialization.Serializable

@Serializable
data class MediaStoreSong(
    val mediaStoreId: Long,
    val albumId: Long,            // ✅ NEW (stable album grouping)
    val title: String,
    val artist: String,
    val album: String,
    val trackNumber: Int,
    val durationMs: Long,
    val contentUri: String // store as String for JSON safety
) {
    fun identityKey(): String =
        normalize(artist) + "||" + normalize(album) + "||" + normalize(title)

    private fun normalize(s: String): String =
        s.trim().lowercase()
}
