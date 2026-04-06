package com.example.kylesmusicplayerandroid.data.metadata

data class SongEntry(
    val path: String,
    val title: String,
    val albumArtist: String,
    val album: String,
    val trackNumber: String,
    val artwork: String
) {
    val trackNumberInt: Int
        get() = trackNumber.filter { it.isDigit() }.toIntOrNull() ?: 0
}