package com.example.kylesmusicplayerandroid.data.sync

// ------------------------------------------------------------
// SyncState
// Describes the high-level status of the Sync tab.
// ------------------------------------------------------------
data class SyncState(
    val status: String
)

// ------------------------------------------------------------
// PlaylistSyncInfo
// Result of comparing local playlists.json with backup playlists.
// ------------------------------------------------------------
data class PlaylistSyncInfo(
    val isSynced: Boolean,
    val status: String,
    val missingPlaylists: List<String>,
    val extraPlaylists: List<String>
)

// ------------------------------------------------------------
// SongSyncInfo
// Result of comparing SD card songs vs backup metadata.
// ------------------------------------------------------------
data class SongSyncInfo(
    val isSynced: Boolean,
    val status: String,
    val missingSongs: List<String>,
    val extraSongs: List<String>
)


