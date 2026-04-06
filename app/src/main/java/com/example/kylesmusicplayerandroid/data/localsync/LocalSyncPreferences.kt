package com.example.kylesmusicplayerandroid.data.localsync

import android.content.Context

data class LocalSyncStoredState(
    val managedRootUri: String?,
    val initialSyncComplete: Boolean
)

class LocalSyncPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadState(): LocalSyncStoredState {
        return LocalSyncStoredState(
            managedRootUri = prefs.getString(KEY_MANAGED_ROOT_URI, null),
            initialSyncComplete = prefs.getBoolean(KEY_INITIAL_SYNC_COMPLETE, false)
        )
    }

    fun saveManagedRootUri(uri: String?) {
        prefs.edit()
            .putString(KEY_MANAGED_ROOT_URI, uri)
            .apply()
    }

    fun saveInitialSyncComplete(isComplete: Boolean) {
        prefs.edit()
            .putBoolean(KEY_INITIAL_SYNC_COMPLETE, isComplete)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "local_sync_prefs"
        private const val KEY_MANAGED_ROOT_URI = "managed_root_uri"
        private const val KEY_INITIAL_SYNC_COMPLETE = "initial_sync_complete"
    }
}
