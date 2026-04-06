package com.example.kylesmusicplayerandroid.domain.usecase.sync

import android.content.Context
import com.example.kylesmusicplayerandroid.data.metadata.MusicMetadataParser
import com.example.kylesmusicplayerandroid.data.metadata.SongEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Use Case: Load local metadata from app-private storage.
 *
 * Reads metadata.json → returns Map<String, SongEntry>
 */
class LoadMetadataUseCase {

    suspend fun execute(context: Context): Map<String, SongEntry> =
        withContext(Dispatchers.IO) {

            val file = File(context.filesDir, "metadata.json")

            if (!file.exists()) {
                return@withContext emptyMap()
            }

            val text = file.readText()
            if (text.isBlank()) {
                return@withContext emptyMap()
            }

            return@withContext MusicMetadataParser.parse(text)
        }
}
