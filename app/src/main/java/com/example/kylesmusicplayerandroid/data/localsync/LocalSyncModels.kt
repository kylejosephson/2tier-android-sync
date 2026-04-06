package com.example.kylesmusicplayerandroid.data.localsync

data class SyncManifestEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val lastModified: Long
)

data class SyncManifestMeta(
    val entryCount: Int,
    val totalSizeBytes: Long,
    val generatedAtEpochMs: Long
)

data class SyncManifestResult(
    val meta: SyncManifestMeta,
    val entries: List<SyncManifestEntry>
)

enum class SyncServerStatus {
    STOPPED,
    STARTING,
    WAITING_FOR_DESKTOP,
    CONNECTED
}

data class LocalSyncUiState(
    val managedRootSelected: Boolean = false,
    val managedRootUri: String? = null,
    val managedRootLabel: String = "Not selected",
    val initialSyncComplete: Boolean = false,
    val syncModeActive: Boolean = false,
    val serverStatus: SyncServerStatus = SyncServerStatus.STOPPED,
    val deviceName: String = "",
    val ipAddress: String? = null,
    val port: Int = 8765,
    val statusMessage: String = "Select a music folder to begin."
)
