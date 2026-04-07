package com.example.kylesmusicplayerandroid.viewmodel

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.kylesmusicplayerandroid.auth.AuthManager
import com.example.kylesmusicplayerandroid.data.artwork.AlbumArtCacheBuilder
import com.example.kylesmusicplayerandroid.data.localsync.LocalSyncHttpServer
import com.example.kylesmusicplayerandroid.data.localsync.LocalSyncManifestBuilder
import com.example.kylesmusicplayerandroid.data.localsync.LocalSyncNetworkInfo
import com.example.kylesmusicplayerandroid.data.localsync.LocalSyncPreferences
import com.example.kylesmusicplayerandroid.data.localsync.LocalSyncUiState
import com.example.kylesmusicplayerandroid.data.localsync.SyncServerStatus
import com.example.kylesmusicplayerandroid.data.mediastore.MediaStoreCache
import com.example.kylesmusicplayerandroid.data.mediastore.MediaStoreScanner
import com.example.kylesmusicplayerandroid.data.mediastore.MediaStoreSong
import com.example.kylesmusicplayerandroid.data.service.MediaPlaybackService
import com.example.kylesmusicplayerandroid.data.storage.SdCardIndex
import com.example.kylesmusicplayerandroid.data.storage.ensureParentDirectories
import com.example.kylesmusicplayerandroid.data.storage.guessMimeTypeFromFileName
import com.example.kylesmusicplayerandroid.data.storage.parseManagedRelativePath
import com.example.kylesmusicplayerandroid.data.storage.resolveRelativeDocument
import com.example.kylesmusicplayerandroid.data.sync.OneDriveIndex
import com.example.kylesmusicplayerandroid.data.sync.PlaylistSyncInfo
import com.example.kylesmusicplayerandroid.data.sync.SongSyncInfo
import com.example.kylesmusicplayerandroid.domain.usecase.auth.SignInUseCase
import com.example.kylesmusicplayerandroid.domain.usecase.auth.SignOutUseCase
import com.example.kylesmusicplayerandroid.domain.usecase.playlist.ComparePlaylistsUseCase
import com.example.kylesmusicplayerandroid.domain.usecase.playlist.LoadPlaylistsUseCase
import com.example.kylesmusicplayerandroid.domain.usecase.playlist.SyncPlaylistsUseCase
import com.example.kylesmusicplayerandroid.domain.usecase.sync.CompareSongsUseCase
import com.example.kylesmusicplayerandroid.domain.usecase.sync.LoadMetadataUseCase
import com.example.kylesmusicplayerandroid.domain.usecase.sync.SyncSongsUseCase
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AppViewModel(
    private val loadMetadataUseCase: LoadMetadataUseCase,
    private val loadPlaylistsUseCase: LoadPlaylistsUseCase,
    private val comparePlaylistsUseCase: ComparePlaylistsUseCase,
    private val syncPlaylistsUseCase: SyncPlaylistsUseCase,
    private val compareSongsUseCase: CompareSongsUseCase,
    private val syncSongsUseCase: SyncSongsUseCase,
    private val signInUseCase: SignInUseCase,
    private val signOutUseCase: SignOutUseCase
) : ViewModel() {

    companion object {
        private const val TAG = "AppViewModel"

        var appContext: Context? = null

        // Option A: small sample on Sync-tab focus (fast)
        private const val SD_SAMPLE_VERIFY_ON_FOCUS = 5

        // Stable album cache directory under filesDir
        private const val ALBUM_CACHE_DIR = "artwork_albums"

        // SharedPrefs key that SdCardIndex expects
        private const val PREF_KEY_MUSIC_URI = "music_uri"

        // ✅ IMPORTANT: SdCardIndex / SyncSongsUseCase reads from this prefs file
        private const val PREFS_APP = "app_prefs"

        // Index cache files (app private storage)
        private const val OD_CACHE = "onedrive_index.json"
        private const val SD_CACHE = "sdcard_index.json"
        private const val OD_DELTA = "onedrive_delta_link.txt"

        // MediaStore cache file (app private storage)
        private const val MEDIASTORE_CACHE_FILE = "media_store_songs.json"

        // ---- Model B: playback persistence (Truck Ready™) ----
        private const val PREFS_PLAYBACK = "kmp_playback_prefs"
        private const val KEY_LAST_QUEUE = "last_queue_uris"
        private const val KEY_LAST_INDEX = "last_queue_index"
        private const val LOCAL_SYNC_PORT = 8765
        private const val SYNC_PROTOCOL_VERSION = 1
    }

    // ============================================================
    // AUTH STATE
    // ============================================================

    val isSignedIn: StateFlow<Boolean> get() = AuthManager.isSignedIn
    val userEmail: StateFlow<String?> get() = AuthManager.userEmail

    fun startLogin(activity: Activity) = signInUseCase.execute(activity)
    fun logout(ctx: Context) = signOutUseCase.execute(ctx)

    // ============================================================
    // SD CARD URI GATE (FIXED: verify persisted SAF permission)
    // ============================================================

    private val _hasMusicUri = MutableStateFlow(false)
    val hasMusicUri: StateFlow<Boolean> = _hasMusicUri

    private fun readMusicUriFromPrefs(ctx: Context): String? {
        return ctx.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            .getString(PREF_KEY_MUSIC_URI, null)
    }

    private fun clearMusicUriFromPrefs(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
                .edit()
                .remove(PREF_KEY_MUSIC_URI)
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun hasPersistedReadPermission(ctx: Context, treeUri: Uri): Boolean {
        return try {
            val perms = ctx.contentResolver.persistedUriPermissions
            perms.any { p ->
                p.uri == treeUri && p.isReadPermission
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * ✅ Correct gate rule:
     * - We only consider "music folder selected" if BOTH:
     *   1) a saved music_uri exists AND
     *   2) we still hold a persisted READ permission for that exact URI
     */
    private fun refreshHasMusicUri() {
        val ctx = appContext ?: run {
            _hasMusicUri.value = false
            return
        }

        val uriStr = readMusicUriFromPrefs(ctx)
        if (uriStr.isNullOrBlank()) {
            _hasMusicUri.value = false
            return
        }

        val treeUri = try {
            Uri.parse(uriStr)
        } catch (_: Throwable) {
            clearMusicUriFromPrefs(ctx)
            _hasMusicUri.value = false
            return
        }

        val ok = hasPersistedReadPermission(ctx, treeUri)
        if (!ok) {
            Log.w(TAG, "SAF gate: saved music_uri exists but persisted permission is missing. Forcing folder picker.")
            clearMusicUriFromPrefs(ctx)
            _hasMusicUri.value = false
            return
        }

        _hasMusicUri.value = true
    }

    fun persistMusicTreeUri(uri: Uri): Boolean {
        val ctx = appContext ?: return false

        return try {
            val flags =
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

            // Persist SAF permission
            ctx.contentResolver.takePersistableUriPermission(uri, flags)

            val uriStr = uri.toString()

            // ✅ REQUIRED (this is what SdCardIndex / SyncSongsUseCase reads)
            ctx.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY_MUSIC_URI, uriStr)
                .apply()

            refreshHasMusicUri()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to persist music tree URI", t)
            refreshHasMusicUri()
            false
        }
    }

    // ============================================================
    // INDEX BUILD GATE (Option B) — now with live UI feed
    // ============================================================

    enum class IndexBootstrapPhase {
        NOT_STARTED,
        CHECKING,
        BUILDING_ONEDRIVE,
        BUILDING_SDCARD,
        DONE,
        ERROR
    }

    private val _indexPhase = MutableStateFlow(IndexBootstrapPhase.NOT_STARTED)
    val indexPhase: StateFlow<IndexBootstrapPhase> = _indexPhase

    private val _indexStatus = MutableStateFlow("Not started")
    val indexStatus: StateFlow<String> = _indexStatus

    private val _indexBench = MutableStateFlow("—")
    val indexBench: StateFlow<String> = _indexBench

    private val _indexError = MutableStateFlow<String?>(null)
    val indexError: StateFlow<String?> = _indexError

    // ✅ Gate “two lines” + “logcat-like” feed
    private val _gateLine1 = MutableStateFlow("—")
    val gateLine1: StateFlow<String> = _gateLine1

    private val _gateLine2 = MutableStateFlow("—")
    val gateLine2: StateFlow<String> = _gateLine2

    private val _gateLog = MutableStateFlow<List<String>>(emptyList())
    val gateLog: StateFlow<List<String>> = _gateLog

    private fun appendGateLog(msg: String) {
        val now = msg.trim()
        if (now.isBlank()) return
        val current = _gateLog.value
        _gateLog.value = (listOf(now) + current).take(60)
    }

    private val bootstrapInFlight = AtomicBoolean(false)
    private var bootstrapJob: Job? = null

    private fun hasCacheFile(ctx: Context, filename: String, minBytes: Long): Boolean {
        val f = File(ctx.filesDir, filename)
        return f.exists() && f.isFile && f.length() >= minBytes
    }

    fun startIndexBootstrapIfNeeded(forceRebuild: Boolean = false) {
        val ctx = appContext ?: return

        // ✅ HARD GUARD: never start SD hashing without SAF permission
        refreshHasMusicUri()
        if (!hasMusicUri.value) {
            _indexPhase.value = IndexBootstrapPhase.ERROR
            _indexError.value = "SD card permission not granted. Please pick your Music folder."
            _indexStatus.value = "Waiting for SD folder permission"
            _indexBench.value = "—"
            _gateLine1.value = "Working on: SD Card"
            _gateLine2.value = "Permission missing — open folder picker"
            appendGateLog("Guard: SAF permission missing; refusing to build SD index.")
            return
        }

        if (!bootstrapInFlight.compareAndSet(false, true)) return

        bootstrapJob?.cancel()
        bootstrapJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _indexError.value = null
                _indexPhase.value = IndexBootstrapPhase.CHECKING
                _indexStatus.value = "Checking index caches…"
                _indexBench.value = "—"

                _gateLine1.value = "Working on: Checking"
                _gateLine2.value = "Checking cached index files…"
                appendGateLog("Checking caches in filesDir…")

                val odOk = !forceRebuild && hasCacheFile(ctx, OD_CACHE, minBytes = 64)
                val sdOk = !forceRebuild && hasCacheFile(ctx, SD_CACHE, minBytes = 64)

                if (odOk && sdOk) {
                    _indexPhase.value = IndexBootstrapPhase.DONE
                    _indexStatus.value = "Indexes ready"
                    _indexBench.value = "Using cached indexes"
                    _gateLine1.value = "Working on: Done"
                    _gateLine2.value = "Using cached indexes"
                    appendGateLog("Indexes already present. Skipping rebuild.")
                    return@launch
                }

                if (forceRebuild) {
                    appendGateLog("Force rebuild: deleting caches…")
                    try { File(ctx.filesDir, OD_CACHE).delete() } catch (_: Throwable) {}
                    try { File(ctx.filesDir, SD_CACHE).delete() } catch (_: Throwable) {}
                    try { File(ctx.filesDir, OD_DELTA).delete() } catch (_: Throwable) {}
                }

                _indexPhase.value = IndexBootstrapPhase.BUILDING_ONEDRIVE
                _indexStatus.value = "Building OneDrive index…"
                _indexBench.value = "Hashing OneDrive files…"

                _gateLine1.value = "Working on: OneDrive"
                _gateLine2.value = "Starting OneDrive index…"
                appendGateLog("OneDrive: starting index build/load…")

                val od = OneDriveIndex.load(
                    context = ctx,
                    forceRebuild = false,
                    onStatus = { s ->
                        _gateLine1.value = "Working on: OneDrive"
                        _gateLine2.value = s
                        appendGateLog("OneDrive: $s")
                    },
                    onProgress = { count ->
                        val m = "Indexed $count OneDrive files…"
                        _gateLine2.value = m
                        appendGateLog("OneDrive: $m")
                    }
                )

                if (od.isEmpty()) {
                    _indexPhase.value = IndexBootstrapPhase.ERROR
                    _indexError.value = "OneDrive index build failed (0 files)."
                    _indexStatus.value = "OneDrive index failed"
                    _indexBench.value = "—"
                    _gateLine1.value = "Working on: OneDrive"
                    _gateLine2.value = "FAILED (0 files)"
                    appendGateLog("OneDrive: FAILED (0 files)")
                    return@launch
                } else {
                    _indexBench.value = "OneDrive files indexed: ${od.size}"
                    appendGateLog("OneDrive: done (${od.size} files)")
                }

                // ✅ Re-check SAF permission right before SD hashing (defensive)
                refreshHasMusicUri()
                if (!hasMusicUri.value) {
                    _indexPhase.value = IndexBootstrapPhase.ERROR
                    _indexError.value = "SD card permission not granted. Please pick your Music folder."
                    _indexStatus.value = "Waiting for SD folder permission"
                    _indexBench.value = "—"
                    _gateLine1.value = "Working on: SD Card"
                    _gateLine2.value = "Permission missing — open folder picker"
                    appendGateLog("Guard: SAF permission missing before SD build; aborting.")
                    return@launch
                }

                _indexPhase.value = IndexBootstrapPhase.BUILDING_SDCARD
                _indexStatus.value = "Building SD card index…"
                _indexBench.value = "Hashing SD files…"

                _gateLine1.value = "Working on: SD Card"
                _gateLine2.value = "Starting SD scan…"
                appendGateLog("SD: starting scan/hash…")

                val ok = SdCardIndex.ensureCacheBuilt(
                    context = ctx,
                    forceRebuild = forceRebuild,
                    onProgress = { p ->
                        _gateLine1.value = "Working on: SD Card"
                        _gateLine2.value =
                            "${p.stage}  (songs=${p.indexedCount} reused=${p.reused} rehashed=${p.rehashed} failed=${p.failed})"
                        appendGateLog("SD: ${p.stage} (songs=${p.indexedCount} r=${p.reused} h=${p.rehashed} f=${p.failed})")
                    }
                )

                if (!ok) {
                    _indexPhase.value = IndexBootstrapPhase.ERROR
                    _indexError.value = "SD index build failed (permission missing or scan failed)."
                    _indexStatus.value = "SD card index failed"
                    _indexBench.value = "—"
                    _gateLine1.value = "Working on: SD Card"
                    _gateLine2.value = "FAILED"
                    appendGateLog("SD: FAILED")
                    return@launch
                }

                appendGateLog("SD: done (cache built)")
                _indexPhase.value = IndexBootstrapPhase.DONE
                _indexStatus.value = "Indexes ready"
                _gateLine1.value = "Working on: Done"
                _gateLine2.value = "Indexes ready"
                appendGateLog("Bootstrap complete ✅")
            } catch (t: Throwable) {
                _indexPhase.value = IndexBootstrapPhase.ERROR
                _indexError.value = t.message ?: t.toString()
                _indexStatus.value = "Index build crashed"
                _indexBench.value = "—"
                _gateLine1.value = "Working on: Error"
                _gateLine2.value = "Crashed: ${_indexError.value}"
                appendGateLog("CRASH: ${_indexError.value}")
            } finally {
                bootstrapInFlight.set(false)
            }
        }
    }

    fun cancelIndexBootstrap() {
        bootstrapJob?.cancel()
        bootstrapJob = null
        bootstrapInFlight.set(false)

        if (_indexPhase.value == IndexBootstrapPhase.BUILDING_ONEDRIVE ||
            _indexPhase.value == IndexBootstrapPhase.BUILDING_SDCARD
        ) {
            _indexPhase.value = IndexBootstrapPhase.NOT_STARTED
            _indexStatus.value = "Cancelled"
            _indexBench.value = "—"
            _gateLine1.value = "Working on: Cancelled"
            _gateLine2.value = "Cancelled"
            appendGateLog("Cancelled by user")
        }
    }

    // ============================================================
    // SYNC UI STATE
    // ============================================================

    private val _localSyncUiState = MutableStateFlow(
        LocalSyncUiState(
            deviceName = buildDeviceName(),
            port = LOCAL_SYNC_PORT
        )
    )
    val localSyncUiState: StateFlow<LocalSyncUiState> = _localSyncUiState

    private var localSyncServer: LocalSyncHttpServer? = null

    private val _syncState = MutableStateFlow("Ready")
    val syncState: StateFlow<String> = _syncState

    private val _playlistSyncInfo = MutableStateFlow(
        PlaylistSyncInfo(false, "Playlists not checked yet", emptyList(), emptyList())
    )
    val playlistSyncInfo: StateFlow<PlaylistSyncInfo> = _playlistSyncInfo

    private val _songSyncInfo = MutableStateFlow(
        SongSyncInfo(false, "Songs not checked yet", emptyList(), emptyList())
    )
    val songSyncInfo: StateFlow<SongSyncInfo> = _songSyncInfo

    private val refreshInFlight = AtomicBoolean(false)
    private var lastSyncRefreshJob: Job? = null

    private fun localSyncPreferences(ctx: Context): LocalSyncPreferences {
        return LocalSyncPreferences(ctx)
    }

    private fun buildDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android Device" }
    }

    private fun buildManagedRootLabel(ctx: Context, uriString: String?): String {
        if (uriString.isNullOrBlank()) return "Not selected"

        return try {
            val uri = Uri.parse(uriString)
            DocumentFile.fromTreeUri(ctx, uri)?.name
                ?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment.orEmpty().ifBlank { "Selected folder" }
        } catch (_: Throwable) {
            "Selected folder"
        }
    }

    private fun reloadLocalSyncState() {
        val ctx = appContext ?: return
        val stored = localSyncPreferences(ctx).loadState()
        val storedManagedRootUri = stored.managedRootUri
        val selected = !storedManagedRootUri.isNullOrBlank() &&
            hasPersistedReadPermission(ctx, Uri.parse(storedManagedRootUri))
        val managedRootUri = if (selected) storedManagedRootUri else null

        if (!selected && !storedManagedRootUri.isNullOrBlank()) {
            localSyncPreferences(ctx).saveManagedRootUri(null)
            localSyncPreferences(ctx).saveInitialSyncComplete(false)
        }

        _localSyncUiState.value = _localSyncUiState.value.copy(
            managedRootSelected = selected,
            managedRootUri = managedRootUri,
            managedRootLabel = buildManagedRootLabel(ctx, managedRootUri),
            initialSyncComplete = selected && stored.initialSyncComplete,
            syncModeActive = false,
            serverStatus = SyncServerStatus.STOPPED,
            ipAddress = null,
            port = LOCAL_SYNC_PORT,
            deviceName = buildDeviceName(),
            statusMessage = when {
                !selected -> "Select a managed music folder to prepare Android for sync."
                stored.initialSyncComplete -> "Android is ready for normal sync."
                else -> "Android is ready for initial sync."
            }
        )
    }

    fun onManagedRootSelected(uri: Uri?) {
        val ctx = appContext ?: return

        if (uri == null) {
            val current = _localSyncUiState.value
            _localSyncUiState.value = current.copy(
                statusMessage = if (current.managedRootSelected) {
                    "Folder picker cancelled. Keeping existing managed folder."
                } else {
                    "Folder selection cancelled. Setup is still incomplete."
                }
            )
            return
        }

        if (_localSyncUiState.value.syncModeActive) {
            stopSyncMode()
        }

        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        try {
            ctx.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to persist managed root URI permission", t)
            _localSyncUiState.value = _localSyncUiState.value.copy(
                statusMessage = "Unable to save folder permission."
            )
            return
        }

        val prefs = localSyncPreferences(ctx)
        val previousUri = _localSyncUiState.value.managedRootUri
        val newUri = uri.toString()
        val initialSyncComplete = previousUri == newUri && _localSyncUiState.value.initialSyncComplete

        prefs.saveManagedRootUri(newUri)
        prefs.saveInitialSyncComplete(initialSyncComplete)
        reloadLocalSyncState()

        _localSyncUiState.value = _localSyncUiState.value.copy(
            statusMessage = if (initialSyncComplete) {
                "Managed folder updated. Android remains ready for normal sync."
            } else {
                "Managed folder saved. Android is ready for initial sync."
            }
        )
    }

    fun startSyncMode() {
        val ctx = appContext ?: return
        val current = _localSyncUiState.value

        if (!current.managedRootSelected || current.managedRootUri.isNullOrBlank()) {
            _localSyncUiState.value = current.copy(
                statusMessage = "Select a managed music folder before starting sync mode."
            )
            return
        }

        if (current.syncModeActive) return

        _localSyncUiState.value = current.copy(
            syncModeActive = true,
            serverStatus = SyncServerStatus.STARTING,
            statusMessage = "Starting local sync server..."
        )

        viewModelScope.launch(Dispatchers.IO) {
            val ipAddress = LocalSyncNetworkInfo.findLocalIpv4Address()

            try {
                if (localSyncServer == null) {
                    localSyncServer = LocalSyncHttpServer(
                        port = LOCAL_SYNC_PORT,
                        onPing = { protocolVersion -> buildPingResponse(protocolVersion) },
                        onManifest = { buildManifestResponse() },
                        onReceiveFile = { query, body -> buildReceiveFileResponse(query, body) },
                        onSendFile = { query -> buildSendFileResponse(query) },
                        onDeleteFile = { query -> buildDeleteFileResponse(query) },
                        onRequestObserved = { markDesktopRequestObserved() }
                    )
                }

                localSyncServer?.start()

                _localSyncUiState.value = _localSyncUiState.value.copy(
                    syncModeActive = true,
                    serverStatus = SyncServerStatus.WAITING_FOR_DESKTOP,
                    ipAddress = ipAddress,
                    statusMessage = if (ipAddress.isNullOrBlank()) {
                        "Sync mode is running. Connect over local Wi-Fi."
                    } else {
                        "Waiting for desktop at $ipAddress:$LOCAL_SYNC_PORT"
                    }
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start local sync server", t)
                localSyncServer?.stop()
                localSyncServer = null
                _localSyncUiState.value = _localSyncUiState.value.copy(
                    syncModeActive = false,
                    serverStatus = SyncServerStatus.STOPPED,
                    ipAddress = null,
                    statusMessage = "Unable to start sync server: ${t.message ?: "unknown error"}"
                )
            }
        }
    }

    fun stopSyncMode() {
        localSyncServer?.stop()
        localSyncServer = null

        val current = _localSyncUiState.value
        _localSyncUiState.value = current.copy(
            syncModeActive = false,
            serverStatus = SyncServerStatus.STOPPED,
            ipAddress = null,
            statusMessage = if (current.managedRootSelected) {
                if (current.initialSyncComplete) {
                    "Sync mode stopped. Android is ready for normal sync."
                } else {
                    "Sync mode stopped. Android is ready for initial sync."
                }
            } else {
                "Sync mode stopped. Select a managed music folder to continue."
            }
        )
    }

    fun onSyncTabOpened() {
        reloadLocalSyncState()
    }

    fun onSyncTabClosed() {
        stopSyncMode()
    }

    private data class ManagedRootAccess(
        val context: Context,
        val root: DocumentFile
    )

    private fun markDesktopRequestObserved() {
        val current = _localSyncUiState.value
        if (!current.syncModeActive) return

        _localSyncUiState.value = current.copy(
            serverStatus = SyncServerStatus.CONNECTED,
            statusMessage = "Desktop request received."
        )
    }

    private fun jsonResponse(statusCode: Int, body: JSONObject): LocalSyncHttpServer.Response {
        return LocalSyncHttpServer.Response.json(statusCode, body)
    }

    private fun withManagedRootForFileAction(
        actionName: String,
        block: (ManagedRootAccess) -> LocalSyncHttpServer.Response
    ): LocalSyncHttpServer.Response {
        val ctx = appContext
        val state = _localSyncUiState.value

        if (ctx == null) {
            Log.e(TAG, "$actionName failed: Android context unavailable")
            return jsonResponse(
                500,
                JSONObject()
                    .put("ok", false)
                    .put("message", "Android context unavailable")
            )
        }

        if (!state.syncModeActive) {
            Log.w(TAG, "$actionName rejected: sync mode not active")
            return jsonResponse(
                409,
                JSONObject()
                    .put("ok", false)
                    .put("message", "Sync mode is not active")
            )
        }

        if (!state.managedRootSelected || state.managedRootUri.isNullOrBlank()) {
            Log.w(TAG, "$actionName rejected: managed root missing")
            return jsonResponse(
                409,
                JSONObject()
                    .put("ok", false)
                    .put("message", "Managed root not selected")
            )
        }

        val root = try {
            DocumentFile.fromTreeUri(ctx, Uri.parse(state.managedRootUri))
        } catch (t: Throwable) {
            Log.e(TAG, "$actionName failed: managed root unavailable", t)
            null
        }

        if (root == null) {
            return jsonResponse(
                500,
                JSONObject()
                    .put("ok", false)
                    .put("message", "Managed root is unavailable")
            )
        }

        return block(ManagedRootAccess(context = ctx, root = root))
    }

    private fun buildPingResponse(protocolVersion: Int?): LocalSyncHttpServer.Response {
        if (protocolVersion != null && protocolVersion != SYNC_PROTOCOL_VERSION) {
            return jsonResponse(
                400,
                JSONObject()
                    .put("ok", false)
                    .put("message", "Protocol version mismatch")
            )
        }

        val state = _localSyncUiState.value
        return jsonResponse(
            200,
            JSONObject()
                .put("ok", true)
                .put("message", "Android sync server ready")
                .put("protocolVersion", SYNC_PROTOCOL_VERSION)
                .put("deviceName", state.deviceName)
                .put("syncModeActive", state.syncModeActive)
                .put("managedRootSelected", state.managedRootSelected)
                .put("initialSyncComplete", state.initialSyncComplete)
                .put("transport", "wifi")
        )
    }

    private fun buildManifestResponse(): LocalSyncHttpServer.Response {
        val ctx = appContext
        val state = _localSyncUiState.value

        if (ctx == null) {
            return jsonResponse(
                500,
                JSONObject()
                    .put("ok", false)
                    .put("message", "Android context unavailable")
            )
        }

        if (!state.syncModeActive) {
            return jsonResponse(
                409,
                JSONObject()
                    .put("ok", false)
                    .put("message", "Sync mode is not active")
            )
        }

        if (!state.managedRootSelected || state.managedRootUri.isNullOrBlank()) {
            return jsonResponse(
                409,
                JSONObject()
                    .put("ok", false)
                    .put("message", "Managed root not selected")
            )
        }

        if (!state.initialSyncComplete) {
            return jsonResponse(
                409,
                JSONObject()
                    .put("ok", false)
                    .put("message", "Initial sync not complete")
            )
        }

        return try {
            val managedRootUri = state.managedRootUri
                ?: throw IllegalStateException("Managed root not selected")
            val manifest = LocalSyncManifestBuilder.build(ctx, managedRootUri)
            val entries = JSONArray()
            manifest.entries.forEach { entry ->
                entries.put(
                    JSONObject()
                        .put("relativePath", entry.relativePath)
                        .put("sizeBytes", entry.sizeBytes)
                        .put("lastModified", entry.lastModified)
                )
            }

            jsonResponse(
                200,
                JSONObject()
                    .put("ok", true)
                    .put("message", "Manifest generated successfully")
                    .put(
                        "meta",
                        JSONObject()
                            .put("entryCount", manifest.meta.entryCount)
                            .put("totalSizeBytes", manifest.meta.totalSizeBytes)
                            .put("generatedAtEpochMs", manifest.meta.generatedAtEpochMs)
                    )
                    .put("entries", entries)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Manifest refresh failed", t)
            jsonResponse(
                500,
                JSONObject()
                    .put("ok", false)
                    .put("message", t.message ?: "Manifest refresh failure")
            )
        }
    }

    private fun buildReceiveFileResponse(
        query: Map<String, String>,
        body: ByteArray
    ): LocalSyncHttpServer.Response {
        val rawRelativePath = query["relativePath"]
        Log.i(TAG, "receive-file requested for relative path=${rawRelativePath.orEmpty()}")

        val relativePath = parseManagedRelativePath(rawRelativePath)
            ?: run {
                Log.w(TAG, "receive-file rejected: invalid path=${rawRelativePath.orEmpty()}")
                return jsonResponse(
                    400,
                    JSONObject()
                        .put("ok", false)
                        .put("message", "Invalid relative path")
                        .put("relativePath", rawRelativePath ?: JSONObject.NULL)
                )
            }

        val expectedSizeRaw = query["expectedSize"]
        val expectedSize = when {
            expectedSizeRaw.isNullOrBlank() -> null
            else -> expectedSizeRaw.toLongOrNull()
        }
        if (!expectedSizeRaw.isNullOrBlank() && expectedSize == null) {
            return jsonResponse(
                400,
                JSONObject()
                    .put("ok", false)
                    .put("message", "Invalid expectedSize")
                    .put("relativePath", relativePath.normalizedPath)
            )
        }

        val overwrite = query["overwrite"]?.equals("true", ignoreCase = true) == true

        if (expectedSize != null && expectedSize != body.size.toLong()) {
            Log.w(TAG, "receive-file rejected: expectedSize mismatch for ${relativePath.normalizedPath}")
            return jsonResponse(
                400,
                JSONObject()
                    .put("ok", false)
                    .put("message", "Request body size does not match expectedSize")
                    .put("relativePath", relativePath.normalizedPath)
                    .put("expectedSize", expectedSize)
                    .put("actualSize", body.size)
            )
        }

        return withManagedRootForFileAction("receive-file") { access ->
            try {
                val existing = resolveRelativeDocument(access.root, relativePath)
                if (existing != null && existing.isDirectory) {
                    Log.w(TAG, "receive-file conflict: target path is a directory ${relativePath.normalizedPath}")
                    return@withManagedRootForFileAction jsonResponse(
                        409,
                        JSONObject()
                            .put("ok", false)
                            .put("message", "Target path already exists as a directory")
                            .put("relativePath", relativePath.normalizedPath)
                            .put("conflict", true)
                    )
                }

                val overwriting = existing != null
                if (overwriting && !overwrite) {
                    Log.w(TAG, "receive-file conflict: existing file ${relativePath.normalizedPath}")
                    return@withManagedRootForFileAction jsonResponse(
                        409,
                        JSONObject()
                            .put("ok", false)
                            .put("message", "File already exists")
                            .put("relativePath", relativePath.normalizedPath)
                            .put("conflict", true)
                            .put("created", false)
                            .put("overwritten", false)
                    )
                }

                val parentDir = ensureParentDirectories(access.root, relativePath)
                    ?: return@withManagedRootForFileAction jsonResponse(
                        500,
                        JSONObject()
                            .put("ok", false)
                            .put("message", "Unable to create parent folders")
                            .put("relativePath", relativePath.normalizedPath)
                    )

                val target = existing ?: parentDir.createFile(
                    guessMimeTypeFromFileName(relativePath.fileName),
                    relativePath.fileName
                )

                if (target == null) {
                    Log.e(TAG, "receive-file failed: could not create file ${relativePath.normalizedPath}")
                    return@withManagedRootForFileAction jsonResponse(
                        500,
                        JSONObject()
                            .put("ok", false)
                            .put("message", "Unable to create target file")
                            .put("relativePath", relativePath.normalizedPath)
                    )
                }

                val wrote = access.context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    output.write(body)
                    output.flush()
                    true
                } ?: false

                if (!wrote) {
                    Log.e(TAG, "receive-file failed: SAF write failure ${relativePath.normalizedPath}")
                    return@withManagedRootForFileAction jsonResponse(
                        500,
                        JSONObject()
                            .put("ok", false)
                            .put("message", "SAF write failure")
                            .put("relativePath", relativePath.normalizedPath)
                    )
                }

                Log.i(TAG, "receive-file success: ${relativePath.normalizedPath}")
                jsonResponse(
                    if (overwriting) 200 else 201,
                    JSONObject()
                        .put("ok", true)
                        .put("message", if (overwriting) "File overwritten" else "File created")
                        .put("relativePath", relativePath.normalizedPath)
                        .put("action", if (overwriting) "overwritten" else "created")
                        .put("created", !overwriting)
                        .put("overwritten", overwriting)
                        .put("sizeBytes", body.size)
                )
            } catch (t: Throwable) {
                Log.e(TAG, "receive-file failed for ${relativePath.normalizedPath}", t)
                jsonResponse(
                    500,
                    JSONObject()
                        .put("ok", false)
                        .put("message", t.message ?: "Receive file failed")
                        .put("relativePath", relativePath.normalizedPath)
                )
            }
        }
    }

    private fun buildSendFileResponse(query: Map<String, String>): LocalSyncHttpServer.Response {
        val rawRelativePath = query["relativePath"]
        Log.i(TAG, "send-file requested for relative path=${rawRelativePath.orEmpty()}")

        val relativePath = parseManagedRelativePath(rawRelativePath)
            ?: run {
                Log.w(TAG, "send-file rejected: invalid path=${rawRelativePath.orEmpty()}")
                return jsonResponse(
                    400,
                    JSONObject()
                        .put("ok", false)
                        .put("message", "Invalid relative path")
                        .put("relativePath", rawRelativePath ?: JSONObject.NULL)
                )
            }

        return withManagedRootForFileAction("send-file") { access ->
            try {
                val target = resolveRelativeDocument(access.root, relativePath)
                if (target == null || !target.isFile) {
                    Log.w(TAG, "send-file missing: ${relativePath.normalizedPath}")
                    return@withManagedRootForFileAction jsonResponse(
                        404,
                        JSONObject()
                            .put("ok", false)
                            .put("message", "File not found")
                            .put("relativePath", relativePath.normalizedPath)
                    )
                }

                val bytes = access.context.contentResolver.openInputStream(target.uri)?.use { input ->
                    input.readBytes()
                } ?: return@withManagedRootForFileAction jsonResponse(
                    500,
                    JSONObject()
                        .put("ok", false)
                        .put("message", "Unable to open file for reading")
                        .put("relativePath", relativePath.normalizedPath)
                )

                Log.i(TAG, "send-file success: ${relativePath.normalizedPath}")
                LocalSyncHttpServer.Response(
                    statusCode = 200,
                    bodyBytes = bytes,
                    contentType = guessMimeTypeFromFileName(relativePath.fileName),
                    extraHeaders = mapOf(
                        "Content-Disposition" to "attachment; filename=${relativePath.fileName}",
                        "X-Relative-Path" to relativePath.normalizedPath
                    )
                )
            } catch (t: Throwable) {
                Log.e(TAG, "send-file failed for ${relativePath.normalizedPath}", t)
                jsonResponse(
                    500,
                    JSONObject()
                        .put("ok", false)
                        .put("message", t.message ?: "Send file failed")
                        .put("relativePath", relativePath.normalizedPath)
                )
            }
        }
    }

    private fun buildDeleteFileResponse(query: Map<String, String>): LocalSyncHttpServer.Response {
        val rawRelativePath = query["relativePath"]
        Log.i(TAG, "delete-file requested for relative path=${rawRelativePath.orEmpty()}")

        val relativePath = parseManagedRelativePath(rawRelativePath)
            ?: run {
                Log.w(TAG, "delete-file rejected: invalid path=${rawRelativePath.orEmpty()}")
                return jsonResponse(
                    400,
                    JSONObject()
                        .put("ok", false)
                        .put("message", "Invalid relative path")
                        .put("relativePath", rawRelativePath ?: JSONObject.NULL)
                )
            }

        return withManagedRootForFileAction("delete-file") { access ->
            try {
                val target = resolveRelativeDocument(access.root, relativePath)
                if (target == null) {
                    Log.w(TAG, "delete-file missing: ${relativePath.normalizedPath}")
                    return@withManagedRootForFileAction jsonResponse(
                        404,
                        JSONObject()
                            .put("ok", false)
                            .put("message", "File not found")
                            .put("relativePath", relativePath.normalizedPath)
                    )
                }

                if (!target.isFile) {
                    Log.w(TAG, "delete-file rejected: target is not a file ${relativePath.normalizedPath}")
                    return@withManagedRootForFileAction jsonResponse(
                        409,
                        JSONObject()
                            .put("ok", false)
                            .put("message", "Target path is not a file")
                            .put("relativePath", relativePath.normalizedPath)
                    )
                }

                val deleted = try {
                    target.delete()
                } catch (t: Throwable) {
                    Log.e(TAG, "delete-file failed during delete ${relativePath.normalizedPath}", t)
                    false
                }

                if (!deleted) {
                    return@withManagedRootForFileAction jsonResponse(
                        500,
                        JSONObject()
                            .put("ok", false)
                            .put("message", "Delete failed")
                            .put("relativePath", relativePath.normalizedPath)
                    )
                }

                Log.i(TAG, "delete-file success: ${relativePath.normalizedPath}")
                jsonResponse(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("message", "File deleted")
                        .put("relativePath", relativePath.normalizedPath)
                        .put("action", "deleted")
                )
            } catch (t: Throwable) {
                Log.e(TAG, "delete-file failed for ${relativePath.normalizedPath}", t)
                jsonResponse(
                    500,
                    JSONObject()
                        .put("ok", false)
                        .put("message", t.message ?: "Delete file failed")
                        .put("relativePath", relativePath.normalizedPath)
                )
            }
        }
    }

    // ============================================================
    // PLAYER STATE (QUEUE + NOW PLAYING)
    // ============================================================

    var playerQueue by mutableStateOf<List<MediaStoreSong>>(emptyList())
        private set

    var playerQueueIndex by mutableStateOf(0)
        private set

    var playerBannerMessage by mutableStateOf("Ready")
        private set

    val nowPlayingSong: MediaStoreSong?
        get() = playerQueue.getOrNull(playerQueueIndex)

    private fun trackSortKey(tn: Int): Int = if (tn > 0) tn else Int.MAX_VALUE

    private fun updateBannerFor(song: MediaStoreSong?) {
        playerBannerMessage = if (song == null) {
            "Ready"
        } else {
            val trackPrefix = if (song.trackNumber > 0) {
                song.trackNumber.toString().padStart(2, '0') + " - "
            } else {
                ""
            }
            "🎵 Now Playing: $trackPrefix${song.title}"
        }
    }

    // ============================================================
    // MODEL B: MediaController
    // ============================================================

    private var controller: MediaController? = null
    private var controllerConnecting = AtomicBoolean(false)
    private var positionJob: Job? = null

    var isPlaying by mutableStateOf(false)
        private set

    var playbackPositionMs by mutableStateOf(0L)
        private set

    var playbackDurationMs by mutableStateOf(0L)
        private set

    private val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
            isPlaying = isPlayingNow
            if (isPlayingNow) startPositionUpdates() else stopPositionUpdates()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller ?: return
            if (playbackState == Player.STATE_READY) {
                playbackDurationMs = c.duration.coerceAtLeast(0L)
                playbackPositionMs = c.currentPosition.coerceAtLeast(0L)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller ?: return
            val maxIdx = (playerQueue.size - 1).coerceAtLeast(0)
            val idx = c.currentMediaItemIndex.coerceIn(0, maxIdx)

            if (playerQueue.isNotEmpty()) {
                playerQueueIndex = idx
                persistPlaybackState()
                updateBannerFor(nowPlayingSong)
            } else {
                playerQueueIndex = 0
                persistPlaybackState()
                updateBannerFor(null)
            }
        }
    }

    private fun getSessionToken(ctx: Context): SessionToken {
        return SessionToken(ctx, ComponentName(ctx, MediaPlaybackService::class.java))
    }

    private fun ensureController() {
        val ctx = appContext ?: return
        if (controller != null) return
        if (!controllerConnecting.compareAndSet(false, true)) return

        MediaPlaybackService.ensureServiceRunning(ctx)

        val token = getSessionToken(ctx)
        val future = MediaController.Builder(ctx, token).buildAsync()

        future.addListener(
            {
                try {
                    val c = future.get()
                    controller = c
                    c.addListener(controllerListener)

                    isPlaying = c.isPlaying
                    playbackPositionMs = c.currentPosition.coerceAtLeast(0L)
                    playbackDurationMs = c.duration.coerceAtLeast(0L)

                    if (playerQueue.isNotEmpty()) {
                        syncControllerPlaylistToQueue(
                            c = c,
                            queue = playerQueue,
                            startIndex = playerQueueIndex,
                            keepPlayState = true
                        )
                    } else {
                        restorePlaybackStateIntoControllerIfPossible(c)
                    }

                    if (c.isPlaying) startPositionUpdates()

                    Log.w(TAG, "MediaController connected ✅")
                } catch (t: Throwable) {
                    Log.e(TAG, "MediaController connect failed", t)
                    controller = null
                } finally {
                    controllerConnecting.set(false)
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    private fun buildMediaItemsFromQueue(queue: List<MediaStoreSong>): List<MediaItem> {
        return queue.map { s ->
            MediaItem.Builder()
                .setUri(s.contentUri)
                .setMediaId(s.contentUri.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist)
                        .setAlbumTitle(s.album)
                        .build()
                )
                .build()
        }
    }

    private fun buildMediaItemsFromSongs(songs: List<MediaStoreSong>): List<MediaItem> {
        return songs.map { s ->
            MediaItem.Builder()
                .setUri(s.contentUri)
                .setMediaId(s.contentUri.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist)
                        .setAlbumTitle(s.album)
                        .build()
                )
                .build()
        }
    }

    private fun syncControllerPlaylistToQueue(
        c: MediaController,
        queue: List<MediaStoreSong>,
        startIndex: Int,
        keepPlayState: Boolean
    ) {
        val wasPlaying = c.isPlaying
        val idx = startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))

        if (queue.isEmpty()) {
            c.stop()
            c.clearMediaItems()
            playbackPositionMs = 0L
            playbackDurationMs = 0L
            isPlaying = false
            persistPlaybackState()
            return
        }

        val mediaItems = buildMediaItemsFromQueue(queue)
        c.setMediaItems(mediaItems, idx, 0L)
        c.prepare()

        if (keepPlayState && wasPlaying) c.play() else c.pause()
    }

    private fun setQueueInternal(newQueue: List<MediaStoreSong>, startIndex: Int) {
        playerQueue = newQueue
        playerQueueIndex = startIndex.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
        updateBannerFor(nowPlayingSong)
        persistPlaybackState()

        ensureController()
        controller?.let { c ->
            syncControllerPlaylistToQueue(c, playerQueue, playerQueueIndex, keepPlayState = true)
        }
    }

    private fun appendToQueue(items: List<MediaStoreSong>) {
        if (items.isEmpty()) return

        val wasEmpty = playerQueue.isEmpty()
        val newQueue = playerQueue + items
        val newIndex = if (wasEmpty) 0 else playerQueueIndex

        playerQueue = newQueue
        playerQueueIndex = newIndex.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
        updateBannerFor(nowPlayingSong)
        persistPlaybackState()

        ensureController()
        val c = controller ?: return

        try {
            if (c.mediaItemCount == 0) {
                syncControllerPlaylistToQueue(c, playerQueue, playerQueueIndex, keepPlayState = true)
                return
            }

            val toAdd = buildMediaItemsFromSongs(items)
            c.addMediaItems(toAdd)
        } catch (t: Throwable) {
            Log.e(TAG, "appendToQueue: controller append failed; falling back to full sync", t)
            try {
                syncControllerPlaylistToQueue(c, playerQueue, playerQueueIndex, keepPlayState = true)
            } catch (_: Throwable) {
            }
        }
    }

    fun playNowFromList(songs: List<MediaStoreSong>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        setQueueInternal(songs, startIndex)
        ensureController()
        controller?.play()
    }

    fun clearQueue() {
        stopAndResetPlayback()
        setQueueInternal(emptyList(), 0)
    }

    fun randomizeQueue() {
        if (playerQueue.size <= 1) return
        setQueueInternal(playerQueue.shuffled(), 0)
    }

    fun nextSong() {
        ensureController()
        controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    }

    fun previousSong() {
        ensureController()
        val c = controller ?: return
        if (!c.hasPreviousMediaItem()) {
            c.seekTo(0L)
            return
        }
        c.seekToPreviousMediaItem()
    }

    fun selectQueueIndex(index: Int) {
        if (playerQueue.isEmpty()) return
        if (index !in playerQueue.indices) return

        playerQueueIndex = index
        updateBannerFor(nowPlayingSong)
        persistPlaybackState()

        ensureController()
        controller?.seekTo(index, 0L)
    }

    fun togglePlayPause() {
        ensureController()
        val c = controller ?: run {
            playerBannerMessage = "Connecting playback…"
            return
        }

        if (playerQueue.isNotEmpty() && c.mediaItemCount == 0) {
            syncControllerPlaylistToQueue(
                c = c,
                queue = playerQueue,
                startIndex = playerQueueIndex,
                keepPlayState = false
            )
        }

        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        val dur = playbackDurationMs.coerceAtLeast(0L)
        val target = positionMs.coerceIn(0L, if (dur > 0L) dur else Long.MAX_VALUE)
        c.seekTo(target)
        playbackPositionMs = target
    }

    private fun startPositionUpdates() {
        if (positionJob != null) return
        positionJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                val c = controller
                if (c != null) {
                    playbackPositionMs = c.currentPosition.coerceAtLeast(0L)
                    playbackDurationMs = c.duration.coerceAtLeast(0L)
                    isPlaying = c.isPlaying
                }
                delay(250L)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun stopAndResetPlayback() {
        stopPositionUpdates()
        playbackPositionMs = 0L
        playbackDurationMs = 0L
        isPlaying = false

        controller?.let {
            try {
                it.stop()
                it.clearMediaItems()
            } catch (_: Throwable) {
            }
        }
    }

    private fun persistPlaybackState() {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
            val arr = JSONArray()
            playerQueue.forEach { arr.put(it.contentUri.toString()) }

            prefs.edit()
                .putString(KEY_LAST_QUEUE, arr.toString())
                .putInt(KEY_LAST_INDEX, playerQueueIndex)
                .apply()
        } catch (_: Throwable) {
        }
    }

    private fun restorePlaybackStateIntoControllerIfPossible(c: MediaController) {
        val ctx = appContext ?: return
        if (mediaStoreSongs.isEmpty()) return

        try {
            val prefs = ctx.getSharedPreferences(PREFS_PLAYBACK, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_LAST_QUEUE, null) ?: return
            val idx = prefs.getInt(KEY_LAST_INDEX, 0)

            val arr = JSONArray(raw)
            if (arr.length() <= 0) return

            val byUri = mediaStoreSongs.associateBy { it.contentUri.toString() }
            val restored = ArrayList<MediaStoreSong>(arr.length())
            for (i in 0 until arr.length()) {
                val u = arr.optString(i)
                val s = byUri[u]
                if (s != null) restored.add(s)
            }
            if (restored.isEmpty()) return

            playerQueue = restored
            playerQueueIndex = idx.coerceIn(0, restored.size - 1)
            updateBannerFor(nowPlayingSong)
            syncControllerPlaylistToQueue(c, restored, playerQueueIndex, keepPlayState = false)

            Log.w(TAG, "Restored last queue (${restored.size} songs) index=$playerQueueIndex")
        } catch (_: Throwable) {
        }
    }

    // ============================================================
    // ALBUM ART CACHE BOOTSTRAP (AUTO, ONE-TIME)
    // ============================================================

    private val albumArtBootstrapInFlight = AtomicBoolean(false)

    private fun ensureAlbumArtCacheBootstrapped() {
        val ctx = appContext ?: return
        if (mediaStoreSongs.isEmpty()) return
        if (!albumArtBootstrapInFlight.compareAndSet(false, true)) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = File(ctx.filesDir, ALBUM_CACHE_DIR)
                val alreadyHasAny =
                    dir.exists() && dir.isDirectory && (dir.listFiles()?.isNotEmpty() == true)
                if (alreadyHasAny) return@launch

                AlbumArtCacheBuilder.buildAllAlbumArtCache(ctx, mediaStoreSongs)
            } finally {
                albumArtBootstrapInFlight.set(false)
            }
        }
    }

    // ============================================================
    // INITIALIZATION
    // ============================================================

    fun initialize(context: Context) {
        appContext = context.applicationContext
        refreshHasMusicUri()
        reloadLocalSyncState()
        ensureController()

        viewModelScope.launch(Dispatchers.IO) {
            AuthManager.ensureValidAccessToken()
        }
    }

    // ============================================================
    // MEDIASTORE (ANDROID TRUTH — UI ONLY)
    // ============================================================

    var mediaStoreSongs: List<MediaStoreSong> = emptyList()
        private set

    private suspend fun loadMediaStoreSongs(forceRescan: Boolean = false) {
        val ctx = appContext ?: return

        if (forceRescan) {
            try { File(ctx.filesDir, MEDIASTORE_CACHE_FILE).delete() } catch (_: Throwable) {}

            val scanned = MediaStoreScanner.scan(ctx)
            mediaStoreSongs = scanned
            MediaStoreCache.save(ctx, scanned)

            rebuildArtists()
            ensureAlbumArtCacheBootstrapped()

            controller?.let { c ->
                withContext(Dispatchers.Main.immediate) {
                    if (c.mediaItemCount == 0) restorePlaybackStateIntoControllerIfPossible(c)
                }
            }
            return
        }

        val cached = MediaStoreCache.load(ctx)
        if (cached != null) {
            mediaStoreSongs = cached
            rebuildArtists()
            ensureAlbumArtCacheBootstrapped()

            controller?.let { c ->
                withContext(Dispatchers.Main.immediate) {
                    if (c.mediaItemCount == 0) restorePlaybackStateIntoControllerIfPossible(c)
                }
            }
            return
        }

        val scanned = MediaStoreScanner.scan(ctx)
        mediaStoreSongs = scanned
        MediaStoreCache.save(ctx, scanned)

        rebuildArtists()
        ensureAlbumArtCacheBootstrapped()

        controller?.let { c ->
            withContext(Dispatchers.Main.immediate) {
                if (c.mediaItemCount == 0) restorePlaybackStateIntoControllerIfPossible(c)
            }
        }
    }

    fun onAudioPermissionGranted() {
        viewModelScope.launch(Dispatchers.IO) {
            loadMediaStoreSongs(forceRescan = false)
        }
    }

    // ============================================================
    // LIBRARY STATE (COMPOSE)
    // ============================================================

    var libraryArtists by mutableStateOf<List<String>>(emptyList())
        private set

    var libraryAlbums by mutableStateOf<List<String>>(emptyList())
        private set

    var currentArtist by mutableStateOf<String?>(null)
        private set

    var currentAlbum by mutableStateOf<String?>(null)
        private set

    var artistsScrollIndex by mutableStateOf(0)
        private set
    var artistsScrollOffset by mutableStateOf(0)
        private set

    var albumsScrollIndex by mutableStateOf(0)
        private set
    var albumsScrollOffset by mutableStateOf(0)
        private set

    fun saveArtistsScroll(index: Int, offset: Int) {
        artistsScrollIndex = index
        artistsScrollOffset = offset
    }

    fun saveAlbumsScroll(index: Int, offset: Int) {
        albumsScrollIndex = index
        albumsScrollOffset = offset
    }

    private fun rebuildArtists() {
        libraryArtists =
            mediaStoreSongs
                .mapNotNull { it.artist.takeIf { a -> a.isNotBlank() } }
                .distinct()
                .sorted()

        libraryAlbums = emptyList()
        currentArtist = null
        currentAlbum = null
    }

    private fun rebuildAlbumsForArtist(artist: String) {
        libraryAlbums =
            mediaStoreSongs
                .filter { it.artist == artist }
                .mapNotNull { it.album.takeIf { a -> a.isNotBlank() } }
                .distinct()
                .sorted()
    }

    fun buildAlbumArtCache() {
        val ctx = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            AlbumArtCacheBuilder.buildAllAlbumArtCache(ctx, mediaStoreSongs)
        }
    }

    fun selectArtist(artist: String) {
        currentArtist = artist
        currentAlbum = null
        rebuildAlbumsForArtist(artist)
    }

    fun selectAlbum(album: String) {
        currentAlbum = album
    }

    fun goBackLibrary() {
        when {
            currentAlbum != null -> currentAlbum = null
            currentArtist != null -> {
                currentArtist = null
                libraryAlbums = emptyList()
            }
        }
    }

    fun onSongClicked(song: MediaStoreSong) {
        appendToQueue(listOf(song))
        println("🎵 Added song to queue: ${song.artist} - ${song.title}")
    }

    fun addAlbumToQueue(artist: String, album: String) {
        val albumSongs = mediaStoreSongs
            .filter { it.artist == artist && it.album == album }
            .sortedWith(compareBy({ trackSortKey(it.trackNumber) }, { it.title.lowercase() }))

        appendToQueue(albumSongs)
        println("🎵 Added album to queue: $artist - $album (${albumSongs.size} songs)")
    }

    // ============================================================
    // LEGACY CLOUD SYNC HELPERS (retained for non-Sync-tab behavior)
    // ============================================================

    fun cancelSyncRefresh() {
        lastSyncRefreshJob?.cancel()
        lastSyncRefreshJob = null
        refreshInFlight.set(false)
    }

    fun refreshSyncStatus() {
        val ctx = appContext ?: return
        if (!refreshInFlight.compareAndSet(false, true)) return

        lastSyncRefreshJob?.cancel()
        lastSyncRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                refreshSyncStatusInternal_NoGate(ctx, SD_SAMPLE_VERIFY_ON_FOCUS)
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    private suspend fun refreshSyncStatusInternal_NoGate(
        ctx: Context,
        sdSampleVerifyCount: Int
    ) {
        _syncState.value = "Checking auth…"
        val ok = AuthManager.ensureValidAccessToken()
        if (!ok) {
            _syncState.value = "Sign-in required"
            return
        }

        _syncState.value = "Checking playlists…"
        val playlistFile = File(ctx.filesDir, "playlists.json")
        _playlistSyncInfo.value =
            comparePlaylistsUseCase.execute(
                ctx,
                "/me/drive/root:/MusicPlayerData",
                playlistFile
            )

        _syncState.value = "Checking songs…"
        _songSyncInfo.value = compareSongsUseCase.execute(
            context = ctx,
            sdSampleVerifyCount = sdSampleVerifyCount
        )

        _syncState.value = "Sync status updated"
    }

    fun performPlaylistSync() {
        val ctx = appContext ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _syncState.value = "Syncing playlists…"

            val ok = AuthManager.ensureValidAccessToken()
            if (!ok) {
                _syncState.value = "Sign-in required"
                return@launch
            }

            syncPlaylistsUseCase.execute(
                ctx,
                "/me/drive/root:/MusicPlayerData"
            )

            refreshSyncStatus()
        }
    }

    fun performSongSync() {
        val ctx = appContext ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _syncState.value = "Syncing songs…"
            syncSongsUseCase.execute(ctx)

            _syncState.value = "Updating library…"
            loadMediaStoreSongs(forceRescan = true)

            // MediaStore can lag indexing right after a new file lands on SD.
            // A short retry makes the Library tab reflect new songs reliably.
            delay(1200L)
            loadMediaStoreSongs(forceRescan = true)

            refreshSyncStatus()
        }
    }

    override fun onCleared() {
        super.onCleared()

        stopSyncMode()
        localSyncServer?.shutdown()
        localSyncServer = null

        stopAndResetPlayback()

        val c = controller
        controller = null
        if (c != null) {
            try {
                c.removeListener(controllerListener)
                c.release()
            } catch (_: Throwable) {
            }
        }
    }
}




