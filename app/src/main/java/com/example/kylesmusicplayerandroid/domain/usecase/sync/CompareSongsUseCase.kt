package com.example.kylesmusicplayerandroid.domain.usecase.sync

import android.content.Context
import android.util.Log
import com.example.kylesmusicplayerandroid.data.storage.SdCardIndex
import com.example.kylesmusicplayerandroid.data.sync.OneDriveIndex
import com.example.kylesmusicplayerandroid.data.sync.SongSyncInfo
import java.io.File

// Alias the two different FileHashEntry types to avoid confusion
import com.example.kylesmusicplayerandroid.data.sync.FileHashEntry as OneDriveFileHashEntry
import com.example.kylesmusicplayerandroid.data.storage.FileHashEntry as SdCardFileHashEntry

class CompareSongsUseCase {

    companion object {
        private const val TAG = "CompareSongsUseCase"

        private const val DUMP_ONEDRIVE = "onedrive_index_hashes.txt"
        private const val DUMP_SDCARD = "sdcard_index_hashes.txt"
        private const val OUT_MISSING = "sync_missing.txt"
        private const val OUT_MISMATCHED = "sync_mismatched.txt"

        private const val INCLUDE_MISMATCHES = true

        // Sensible default when callers don't specify (safe + still pretty fast)
        private const val DEFAULT_SD_SAMPLE_VERIFY = 25
    }

    /**
     * @param sdSampleVerifyCount
     *  - Use small (e.g., 5) for Sync-tab focus (fast)
     *  - Use larger (e.g., 25) for "Sync Songs" button (strong verification before writing)
     */
    suspend fun execute(
        context: Context,
        sdSampleVerifyCount: Int = DEFAULT_SD_SAMPLE_VERIFY
    ): SongSyncInfo {

        // 1) Load OneDrive index (REMOTE TRUTH)
        val remote: Map<String, OneDriveFileHashEntry> = OneDriveIndex.load(context)

        if (remote.isEmpty()) {
            return SongSyncInfo(
                isSynced = false,
                status = "Unable to read OneDrive music index",
                missingSongs = emptyList(),
                extraSongs = emptyList()
            )
        }

        Log.w(TAG, "Remote(OneDrive)=${remote.size}")

        // 2) FAST SD index (CACHE-ONLY) — no hashing, no full SAF crawl
        val sdIndex = SdCardIndex.buildFromCacheOnly(
            context = context,
            requiredPaths = remote.keys,
            sampleVerifyCount = sdSampleVerifyCount
        ) ?: return SongSyncInfo(
            isSynced = false,
            status = "Unable to read SD card cache",
            missingSongs = emptyList(),
            extraSongs = emptyList()
        )

        val local: Map<String, SdCardFileHashEntry> = sdIndex.entriesByPath
        Log.w(TAG, "Local(SD-cache-hit)=${local.size} trusted=${sdIndex.isCacheTrusted}")

        // 3) Diagnostic dumps (safe typed extraction, no reflection)
        dumpHashIndexOneDrive(context, DUMP_ONEDRIVE, remote)
        dumpHashIndexSdCard(context, DUMP_SDCARD, local)

        // 4) Compare
        val missing = ArrayList<String>()
        val mismatched = ArrayList<String>()

        for ((path, odEntry) in remote) {
            val sdEntry = local[path]
            if (sdEntry == null) {
                missing.add(path)
                continue
            }

            if (INCLUDE_MISMATCHES && sdEntry.sha256 != odEntry.sha256) {
                mismatched.add("$path | OD=${odEntry.sha256} | SD=${sdEntry.sha256}")
            }
        }

        // 5) Write outputs
        File(context.filesDir, OUT_MISSING).writeText(missing.sorted().joinToString("\n"))
        if (INCLUDE_MISMATCHES) {
            File(context.filesDir, OUT_MISMATCHED).writeText(mismatched.sorted().joinToString("\n"))
        }

        Log.w(TAG, "✅ Missing=${missing.size}  Mismatched=${mismatched.size}")

        // 6) UI result
        val syncedByHashes = missing.isEmpty() && (!INCLUDE_MISMATCHES || mismatched.isEmpty())

        val status = when {
            !sdIndex.isCacheTrusted ->
                "SD cache untrusted (possible external SD change). Press Sync Songs to rebuild/verify."
            missing.isNotEmpty() ->
                "Missing files on SD"
            INCLUDE_MISMATCHES && mismatched.isNotEmpty() ->
                "Hash mismatches found"
            else ->
                "All files verified"
        }

        return SongSyncInfo(
            isSynced = syncedByHashes && sdIndex.isCacheTrusted,
            status = status,
            missingSongs = missing.sorted(),
            extraSongs = emptyList()
        )
    }

    // ============================================================
    // INTERNAL: HASH INDEX DUMPS (typed, no reflection)
    // ============================================================

    private fun dumpHashIndexOneDrive(
        context: Context,
        filename: String,
        entriesByPath: Map<String, OneDriveFileHashEntry>
    ) {
        val file = File(context.filesDir, filename)
        file.bufferedWriter().use { writer ->
            entriesByPath.entries
                .sortedBy { it.key }
                .forEach { (path, entry) ->
                    writer.write("$path | ${entry.sha256} | ${entry.sizeBytes}")
                    writer.newLine()
                }
        }
    }

    private fun dumpHashIndexSdCard(
        context: Context,
        filename: String,
        entriesByPath: Map<String, SdCardFileHashEntry>
    ) {
        val file = File(context.filesDir, filename)
        file.bufferedWriter().use { writer ->
            entriesByPath.entries
                .sortedBy { it.key }
                .forEach { (path, entry) ->
                    writer.write("$path | ${entry.sha256} | ${entry.sizeBytes}")
                    writer.newLine()
                }
        }
    }
}
