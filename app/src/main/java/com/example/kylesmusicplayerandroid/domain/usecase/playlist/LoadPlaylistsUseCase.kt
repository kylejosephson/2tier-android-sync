package com.example.kylesmusicplayerandroid.domain.usecase.playlist

import android.content.Context
import org.json.JSONObject
import java.io.File

class LoadPlaylistsUseCase {

    /**
     * Load local playlists.json into a JSONObject.
     * Creates an empty file if it does not exist.
     *
     * This use case is LOCAL ONLY by design.
     * Cloud playlist backups are handled by:
     *  - ComparePlaylistsUseCase (check-only)
     *  - SyncPlaylistsUseCase (user-initiated overwrite)
     */
    fun execute(context: Context): JSONObject {

        val file = File(context.filesDir, "playlists.json")

        if (!file.exists()) {
            file.writeText("{}")
            return JSONObject()
        }

        val text = file.readText()
        if (text.isBlank()) return JSONObject()

        return try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject() // fallback for corrupted JSON
        }
    }
}
